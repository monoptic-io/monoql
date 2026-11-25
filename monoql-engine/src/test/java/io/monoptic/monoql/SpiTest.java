/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql;

import io.monoptic.monoql.catalog.Catalog;
import io.monoptic.monoql.catalog.CatalogProvider;
import io.monoptic.monoql.catalog.Grant;
import io.monoptic.monoql.catalog.Key;
import io.monoptic.monoql.catalog.MetaTable;
import io.monoptic.monoql.catalog.MetaTables;
import io.monoptic.monoql.catalog.SchemaInfo;
import io.monoptic.monoql.catalog.TableInfo;
import io.monoptic.monoql.catalog.UnsupportedException;
import io.monoptic.monoql.catalog.memory.MemoryMetaTable;
import io.monoptic.monoql.engine.RowCodec;

import org.apache.calcite.sql.parser.SqlAbstractParserImpl;
import org.apache.calcite.sql.parser.SqlParser;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.monoptic.monoql.Sessions.rows;
import static io.monoptic.monoql.Sessions.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests the SPI from an implementor's point of view: catalogs that support
 * only part of the API. */
class SpiTest {
  /** A backend that understands ACLs and nothing else. This is the whole
   * adapter. */
  static class AclCatalog implements Catalog {
    final List<String> calls = new ArrayList<>();
    final MemoryMetaTable<Grant> grants =
        new MemoryMetaTable<>(MetaTables.GRANTS.name()) {
          @Override public void insert(Grant row) {
            calls.add("insert " + row.privilege() + " to " + row.grantee());
            super.insert(row);
          }

          @Override public void delete(Grant row) {
            calls.add("delete " + row.privilege() + " from " + row.grantee());
            super.delete(row);
          }

          @Override public void update(Grant before, Grant after) {
            calls.add("update " + after.privilege() + " grantable="
                + after.grantable());
            super.update(before, after);
          }
        };

    @Override public MetaTable<Grant> grants() {
      return grants;
    }
  }

  @Test void aclOnlyCatalog() {
    final AclCatalog acl = new AclCatalog();
    try (Session session = Monoql.builder().catalog("ACL", acl).build()) {
      assertEquals("ACL", session.currentCatalog());
      run(session, "grant select, insert on s.t to role r",
          "grant select on s.t to role r with grant option",
          "revoke insert on s.t from role r");
      assertEquals(
          List.of("insert SELECT to R", "insert INSERT to R",
              "update SELECT grantable=true", "delete INSERT from R"),
          acl.calls);
      assertEquals(
          List.of("[SELECT, TABLE, S, T, ROLE, R, true, null, null, null]"),
          rows(session, "show grants"));

      // Everything else is unsupported, and says so.
      for (String sql
          : List.of("create schema s", "create table s.t (a int)",
              "create role r", "create view s.v as select 1",
              "create trigger s.tr as 'x'", "fire trigger s.tr",
              "grant role r to user u", "drop table s.t",
              "drop table if exists s.t", "revoke role r from user u",
              "pause trigger s.tr", "describe s.t",
              "comment on table s.t is 'x'")) {
        final UnsupportedException e =
            assertThrows(UnsupportedException.class,
                () -> session.execute(sql), sql);
        assertTrue(e.getMessage().startsWith("This catalog does not support"),
            e.getMessage());
      }
      // Reading an unsupported table across catalogs yields no rows from
      // the catalogs that lack it, rather than failing the query.
      assertEquals(List.of(), rows(session, "select * from metadata.tables"));
      // But a statement aimed at this catalog gets a straight answer.
      assertThrows(UnsupportedException.class,
          () -> session.execute("show schemas"));
      assertThrows(UnsupportedException.class,
          () -> session.execute("show tables in s"));
    }
  }

  /** A backend that can grant and revoke, but cannot say what has been
   * granted. REVOKE still works: it names the rows it removes. */
  @Test void writeOnlyCatalog() {
    final List<String> calls = new ArrayList<>();
    final Catalog blind = new Catalog() {
      @Override public MetaTable<Grant> grants() {
        return new MetaTable<>() {
          @Override public String name() {
            return "GRANTS";
          }

          @Override public java.util.Optional<Grant> get(Key key) {
            return java.util.Optional.empty();
          }

          @Override public void insert(Grant row) {
            calls.add("grant " + row.privilege() + " to " + row.grantee());
          }

          @Override public void delete(Grant row) {
            calls.add("revoke " + row.privilege() + " on "
                + row.objectSchema() + "." + row.objectName() + " from "
                + row.granteeType() + " " + row.grantee());
          }
        };
      }
    };
    try (Session session = Monoql.builder().catalog("B", blind).build()) {
      run(session, "grant select on s.t to role r",
          "revoke select, insert on s.t from role r, user u");
      assertEquals(
          List.of("grant SELECT to R", "revoke SELECT on S.T from ROLE R",
              "revoke SELECT on S.T from USER U",
              "revoke INSERT on S.T from ROLE R",
              "revoke INSERT on S.T from USER U"),
          calls);
      // Updates need to see the row they change.
      assertThrows(UnsupportedException.class,
          () -> session.execute(
              "revoke grant option for select on s.t from role r"));
      assertThrows(UnsupportedException.class,
          () -> session.execute("show grants"));
    }
  }

  /** A catalog that lists tables but cannot produce their rows, like a
   * schema registry. */
  static class ReadOnlyCatalog implements Catalog {
    @Override public MetaTable<SchemaInfo> schemas() {
      return new MetaTable<>() {
        @Override public String name() {
          return "SCHEMAS";
        }

        @Override public Iterable<SchemaInfo> scan() {
          return List.of(new SchemaInfo("REG", "registry", Map.of()));
        }
      };
    }

    @Override public MetaTable<TableInfo> tables() {
      return new MetaTable<>() {
        @Override public String name() {
          return "TABLES";
        }

        @Override public Iterable<TableInfo> scan() {
          return List.of(
              new TableInfo("REG", "EVENTS",
                  List.of(
                      new io.monoptic.monoql.catalog.Column("ID", "BIGINT",
                          false, null),
                      new io.monoptic.monoql.catalog.Column("BODY",
                          "VARCHAR", true, "payload")),
                  List.of("ID"), List.of(), null, Map.of()));
        }
      };
    }
  }

  @Test void metadataOnlyTables() {
    try (Session session =
             Monoql.builder().catalog("R", new ReadOnlyCatalog())
                 .use("R", "REG").build()) {
      assertEquals(List.of("[EVENTS, null]"), rows(session, "show tables"));
      assertEquals(
          List.of("[ID, BIGINT, false, null]", "[BODY, VARCHAR, true, payload]"),
          rows(session, "describe events"));
      // The table can be planned against, even though it cannot be read.
      assertTrue(session.explain("select body from events where id = 1")
          .contains("EnumerableTableScan(table=[[R, REG, EVENTS]])"));
      final UnsupportedException e =
          assertThrows(UnsupportedException.class,
              () -> session.execute("select * from events"));
      assertEquals(
          "Catalog R does not provide the rows of table REG.EVENTS",
          e.getMessage());
      // Scans are supported; writes are not.
      assertThrows(UnsupportedException.class,
          () -> session.execute("drop table events"));
      assertThrows(UnsupportedException.class,
          () -> session.execute("create table t (a int)"));
    }
  }

  /** Catalogs come and go through the provider SPI, and are closed. */
  @Test void providers() {
    final AtomicBoolean closed = new AtomicBoolean();
    final List<Map<String, String>> created = new ArrayList<>();
    final CatalogProvider provider = new CatalogProvider() {
      @Override public String type() {
        return "acl";
      }

      @Override public Catalog create(String name,
          Map<String, String> options) {
        created.add(options);
        return new AclCatalog() {
          @Override public void close() {
            closed.set(true);
          }
        };
      }
    };
    try (Session session = Monoql.builder().provider(provider).build()) {
      run(session,
          "create catalog a with (type = 'ACL', endpoint = 'http://x', "
              + "\"Case.Sensitive\" = 1)",
          "grant select on a.s.t to role r",
          "comment on catalog a is 'access control'");
      assertEquals(
          List.of(Map.of("endpoint", "http://x", "Case.Sensitive", "1")),
          created);
      assertEquals(List.of("[A, ACL, access control]"),
          rows(session, "show catalogs like 'A'"));
      assertEquals(List.of("[A, SELECT]"),
          rows(session, "select catalog_name, privilege from metadata.grants"));
      assertFalse(closed.get());
      run(session, "drop catalog a");
      assertTrue(closed.get());
      assertEquals(List.of(), rows(session, "select * from metadata.grants"));
    }
  }

  /** The default implementation of every part of the SPI is "unsupported". */
  @Test void defaultsAreUnsupported() {
    final Catalog nothing = new Catalog() {
    };
    for (MetaTables.Def<?> def : MetaTables.ALL) {
      final MetaTable<?> table = def.of(nothing);
      assertEquals(def.name(), table.name());
      assertThrows(UnsupportedException.class, table::scan);
      assertThrows(UnsupportedException.class,
          () -> table.get(Key.of("x")));
    }
    assertEquals(Optional.empty(),
        nothing.bind(
            new TableInfo("S", "T", List.of(), List.of(), List.of(), null,
                Map.of())));
  }

  /** No column of the metadata schema needs quoting. */
  @Test void columnNamesAreNotReserved() {
    final SqlAbstractParserImpl.Metadata metadata =
        SqlParser.create("", Monoql.parserConfig()).getMetadata();
    final List<Class<?>> rowTypes = new ArrayList<>();
    MetaTables.ALL.forEach(def -> rowTypes.add(def.rowType()));
    rowTypes.add(io.monoptic.monoql.catalog.CatalogInfo.class);
    rowTypes.add(io.monoptic.monoql.engine.Setting.class);
    rowTypes.add(io.monoptic.monoql.engine.ColumnInfo.class);
    for (Class<?> rowType : rowTypes) {
      for (String column : RowCodec.of(rowType, true).columnNames()) {
        assertFalse(metadata.isReservedWord(column),
            rowType.getSimpleName() + "." + column + " is a reserved word");
      }
    }
  }
}
