/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql;

import io.monoptic.monoql.catalog.AccessDeniedException;
import io.monoptic.monoql.catalog.CatalogException;
import io.monoptic.monoql.catalog.Grant;
import io.monoptic.monoql.catalog.MetaTable;
import io.monoptic.monoql.catalog.memory.MemoryCatalog;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.monoptic.monoql.Sessions.rows;
import static io.monoptic.monoql.Sessions.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests that a session with a user is held to the grants of its
 * catalogs. */
class AuthorizationTest {
  private final MemoryCatalog catalog = new MemoryCatalog();
  /** Acts as nobody: unchecked. Sets things up. */
  private Session root;
  private Session ann;
  private Session bob;

  private Session as(String user) {
    return Monoql.builder().catalog("C", catalog).use("C", "S").user(user)
        .build();
  }

  @BeforeEach void open() {
    root = Monoql.builder().catalog("C", catalog).build();
    run(root, "create schema s", "use c.s",
        "create table t (id int)", "insert into t values (1), (2)",
        "create table u (id int)", "insert into u values (10)");
    ann = as("ANN");
    bob = as("BOB");
  }

  @AfterEach void close() {
    root.close();
    ann.close();
    bob.close();
  }

  private static String denied(Session session, String sql) {
    return assertThrows(AccessDeniedException.class,
        () -> session.execute(sql).close(), sql).getMessage();
  }

  @Test void nothingIsAllowedUntilGranted() {
    assertEquals("User ANN lacks SELECT on C.S.T",
        denied(ann, "select * from t"));
    denied(ann, "insert into t values (3)");
    denied(ann, "update t set id = 0");
    denied(ann, "delete from t");
    denied(ann, "explain plan for select * from t");
    assertThrows(AccessDeniedException.class,
        () -> ann.explain("select * from t"));
    // A session with no user is not checked.
    assertEquals(2, rows(root, "select * from t").size());
  }

  @Test void tablePrivileges() {
    run(root, "grant select on t to user ann");
    assertEquals(List.of("[1]", "[2]"), rows(ann, "select * from t"));
    // SELECT is not INSERT, T is not U, and ANN is not BOB.
    assertEquals("User ANN lacks INSERT on C.S.T",
        denied(ann, "insert into t values (3)"));
    denied(ann, "select * from u");
    denied(ann, "select * from t join u on t.id = u.id");
    denied(ann, "select * from t where id in (select id from u)");
    denied(bob, "select * from t");

    run(root, "grant insert, delete on t to ann");
    run(ann, "insert into t values (3)", "delete from t where id = 1");
    denied(ann, "update t set id = 0");
    // Reading in order to write is still reading.
    denied(ann, "insert into t select id from u");

    run(root, "revoke select on t from user ann");
    denied(ann, "select * from t");

    run(root, "grant all on u to bob");
    run(bob, "insert into u values (11)", "update u set id = 12 where id = 11",
        "delete from u where id = 12");
    assertEquals(List.of("[10]"), rows(bob, "select * from u"));
  }

  @Test void roles() {
    run(root, "create role reader", "create role staff",
        "grant select on t to role reader",
        "grant role reader to role staff",
        "grant role staff to user ann");
    // ANN is STAFF, and STAFF is READER.
    assertEquals(2, rows(ann, "select * from t").size());
    denied(bob, "select * from t");
    run(root, "revoke role reader from role staff");
    denied(ann, "select * from t");
    // A grant to a role is not a grant to a user of the same name.
    run(root, "grant select on u to role bob");
    denied(bob, "select * from u");
  }

  @Test void grantsOnSchemasAndCatalogs() {
    run(root, "grant select on schema s to ann");
    assertEquals(2, rows(ann, "select * from t").size());
    assertEquals(1, rows(ann, "select * from u").size());
    denied(ann, "insert into t values (3)");

    run(root, "create schema other", "create table other.o (id int)",
        "grant insert on catalog c to bob");
    run(bob, "insert into other.o values (1)", "insert into t values (3)");
    denied(bob, "select * from other.o");
    denied(ann, "select * from other.o");
  }

  /** A view is read with its definer's rights. */
  @Test void views() {
    run(root, "create view v as select id + 1 as n from u",
        "grant select on v to ann");
    assertEquals(List.of("[11]"), rows(ann, "select * from v"));
    denied(ann, "select * from u");
    denied(bob, "select * from v");

    // So you cannot define a view over what you cannot read.
    run(root, "create schema mine", "grant all on schema mine to bob");
    assertEquals("User BOB lacks SELECT on C.S.U",
        denied(bob, "create view mine.peek as select * from s.u"));
    run(root, "grant select on u to bob");
    run(bob, "create view mine.peek as select * from s.u");
    assertEquals(List.of("[10]"), rows(bob, "select * from mine.peek"));
  }

  /** DDL, DCL and control statements change metadata, which takes ALL
   * PRIVILEGES on the schema or the catalog. */
  @Test void metadata() {
    assertEquals("User ANN lacks ALL PRIVILEGES on schema C.S, which"
            + " changing METADATA.TABLES takes",
        denied(ann, "create table x (id int)"));
    denied(ann, "drop table t");
    denied(ann, "alter table t add column name varchar");
    denied(ann, "comment on table t is 'mine'");
    denied(ann, "create view w as select 1 as c");
    denied(ann, "create role r");
    // The other spelling of the same thing is the same thing.
    denied(ann, "delete from metadata.tables where table_name = 'T'");
    denied(ann, "insert into metadata.roles (role_name) values ('R')");

    run(root, "grant all on schema s to ann");
    run(ann, "create table x (id int)", "insert into x values (1)",
        "alter table x add column name varchar", "drop table x",
        "grant select on t to user bob");
    assertEquals(2, rows(bob, "select * from t").size());
    // A schema is not the catalog.
    assertEquals("User ANN lacks ALL PRIVILEGES on catalog C, which changing"
            + " METADATA.SCHEMAS takes",
        denied(ann, "create schema more"));
    denied(ann, "create role r");
    denied(ann, "grant select on catalog c to bob");

    run(root, "grant all on catalog c to bob");
    run(bob, "create schema more", "create table more.m (id int)",
        "create role r", "grant role r to ann", "drop table more.m",
        "drop schema more");
  }

  @Test void grantOption() {
    run(root, "grant select on t to ann", "grant insert on t to ann");
    assertEquals("User ANN may not grant or revoke SELECT on C.S.T: that takes"
            + " ALL PRIVILEGES on its schema or catalog, or SELECT WITH GRANT"
            + " OPTION",
        denied(ann, "grant select on t to bob"));
    run(root, "grant select on t to ann with grant option");
    run(ann, "grant select on t to bob");
    assertEquals(2, rows(bob, "select * from t").size());
    // Only what was granted that way, and only where.
    denied(ann, "grant insert on t to bob");
    denied(ann, "grant select on u to bob");
    denied(ann, "grant select on schema s to bob");
    denied(ann, "insert into metadata.grants (privilege, object_type,"
        + " object_schema, object_name, grantee_type, grantee, grantable)"
        + " values ('DELETE', 'TABLE', 'S', 'T', 'USER', 'BOB', false)");
    run(ann, "revoke select on t from bob");
    denied(bob, "select * from t");
  }

  @Test void whatIsOpen() {
    // Metadata can be read by anyone.
    assertEquals(List.of("[T, null]", "[U, null]"), rows(ann, "show tables"));
    assertEquals(1, rows(ann, "describe t").size());
    assertEquals(List.of("[ANN]"),
        rows(ann, "select setting_value from metadata.settings"
            + " where setting_name = 'user'"));
    assertEquals(List.of("[ANN]"), rows(ann, "select current_user"));
    assertEquals(List.of("[" + System.getProperty("user.name") + "]"),
        rows(root, "select current_user"));
    // So can queries that touch no table.
    assertEquals(List.of("[2]"), rows(ann, "select 1 + 1"));
    // Sessions keep their own settings; but not who they are.
    run(root, "create schema other");
    run(ann, "use other", "use c.s");
    final CatalogException e =
        assertThrows(CatalogException.class,
            () -> ann.execute("update metadata.settings"
                + " set setting_value = 'BOB' where setting_name = 'user'"));
    assertTrue(e.getMessage().contains("cannot be changed"), e.getMessage());
    // Mounting a catalog would be a way around all of this.
    denied(ann, "create catalog extra with (type = 'memory')");
    denied(ann, "drop catalog c");
  }

  /** A catalog with no GRANTS table is not monoql's to police. */
  @Test void catalogWithoutGrants() {
    final MemoryCatalog ungoverned = new MemoryCatalog() {
      @Override public MetaTable<Grant> grants() {
        return MetaTable.unsupported("GRANTS");
      }
    };
    try (Session setup = Monoql.builder().catalog("X", ungoverned).build()) {
      run(setup, "create schema s", "create table s.t (id int)");
    }
    try (Session session =
        Monoql.builder().catalog("X", ungoverned).user("ANN").build()) {
      run(session, "insert into x.s.t values (1)", "create table x.s.u (i int)");
      assertEquals(List.of("[1]"), rows(session, "select * from x.s.t"));
    }
  }
}
