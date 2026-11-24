/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql;

import io.monoptic.monoql.catalog.AlreadyExistsException;
import io.monoptic.monoql.catalog.CatalogException;
import io.monoptic.monoql.catalog.NotFoundException;
import io.monoptic.monoql.catalog.TableInfo;
import io.monoptic.monoql.catalog.UnsupportedException;
import io.monoptic.monoql.catalog.memory.MemoryCatalog;

import org.apache.calcite.DataContext;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Linq4j;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.ScannableTable;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.sql.type.SqlTypeName;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static io.monoptic.monoql.Sessions.rows;
import static io.monoptic.monoql.Sessions.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end tests of a session over the in-memory catalog. */
class SessionTest {
  private Session session;

  @BeforeEach void open() {
    session = Monoql.builder().build();
  }

  @AfterEach void close() {
    session.close();
  }

  @Test void defaults() {
    assertEquals("MEMORY", session.currentCatalog());
    assertEquals("MAIN", session.currentSchema());
    assertEquals(List.of("[MEMORY, embedded, null]"),
        rows(session, "show catalogs"));
    assertEquals(
        List.of("[catalog, MEMORY]", "[groups, null]", "[schema, MAIN]",
            "[user, null]"),
        rows(session, "show settings"));
    assertEquals(List.of("[2, x]"), rows(session, "select 1 + 1, 'x'"));
  }

  @Test void tableLifecycle() {
    run(session,
        "create table t (id int not null primary key, name varchar(20) "
            + "comment 'the name', tags varchar array) comment 'c'",
        "insert into t values (1, 'a', array['x', 'y']), (2, 'bb', null)");
    assertEquals(List.of("[2, bb, null]", "[1, a, [x, y]]"),
        rows(session, "select * from t order by id desc"));
    assertEquals(
        List.of("[ID, INTEGER, false, null]",
            "[NAME, VARCHAR(20), true, the name]",
            "[TAGS, VARCHAR ARRAY, true, null]"),
        rows(session, "describe t"));

    assertEquals(1, session.execute("update t set name = 'z' where id = 2")
        .updateCount());
    assertEquals(1, session.execute("delete from t where id = 1")
        .updateCount());
    assertEquals(List.of("[2, z, null]"), rows(session, "select * from t"));

    // Existing rows survive a change of shape.
    run(session, "alter table t add column age int",
        "alter table t drop column tags",
        "alter table t rename to u");
    assertEquals(List.of("[2, z, null]"), rows(session, "select * from u"));
    assertEquals(List.of("[U, c]"), rows(session, "show tables"));

    run(session, "drop table u");
    assertEquals(List.of(), rows(session, "show tables"));
  }

  @Test void createTableAs() {
    run(session, "create table t (id int, name varchar)",
        "insert into t values (1, 'a'), (2, 'b'), (3, 'c')",
        "create table u comment 'copy' as "
            + "select id * 10 as x, upper(name) as y from t where id > 1");
    assertEquals(List.of("[20, B]", "[30, C]"),
        rows(session, "select * from u order by 1"));
    assertEquals(List.of("[X, INTEGER, true, null]", "[Y, VARCHAR, true, null]"),
        rows(session, "describe u"));
  }

  @Test void existence() {
    run(session, "create table t (a int)");
    assertThrows(AlreadyExistsException.class,
        () -> session.execute("create table t (b int)"));
    assertEquals(0,
        session.execute("create table if not exists t (b int)").updateCount());
    assertEquals(List.of("[A, INTEGER, true, null]"),
        rows(session, "describe t"));
    run(session, "create or replace table t (b int)");
    assertEquals(List.of("[B, INTEGER, true, null]"),
        rows(session, "describe t"));

    final NotFoundException e =
        assertThrows(NotFoundException.class,
            () -> session.execute("drop table nosuch"));
    assertEquals("Table MEMORY.MAIN.NOSUCH not found", e.getMessage());
    assertEquals(0,
        session.execute("drop table if exists nosuch").updateCount());
    assertThrows(NotFoundException.class,
        () -> session.execute("pause trigger nosuch"));
    assertThrows(NotFoundException.class,
        () -> session.execute("comment on table nosuch is 'x'"));
    assertThrows(NotFoundException.class,
        () -> session.execute("describe nosuch"));
    // Schemas must be empty to be dropped, and must exist to be used.
    assertThrows(CatalogException.class,
        () -> session.execute("drop schema main"));
    assertThrows(NotFoundException.class,
        () -> session.execute("create table nosuch.t (a int)"));
  }

  @Test void views() {
    run(session, "create table t (id int, name varchar)",
        "insert into t values (1, 'a'), (2, 'b')",
        "create view v as select id, name from t where id > 1",
        "create view w (k, doubled) as select id, id * 2 from v");
    assertEquals(List.of("[2, b]"), rows(session, "select * from v"));
    assertEquals(List.of("[4]"), rows(session, "select doubled from w"));

    // A view means what it meant when it was created, wherever it is
    // read from: its definition is stored fully qualified.
    run(session, "create schema other", "use other",
        "create table t (id int, name varchar)");
    assertEquals(List.of("[2, b]"),
        rows(session, "select * from main.v"));

    // Invalid views are rejected up front.
    assertThrows(MonoqlException.class,
        () -> session.execute("create view bad as select * from nosuch"));
    assertThrows(CatalogException.class,
        () -> session.execute("create view bad (a) as select 1, 2"));
  }

  /** A view's columns are derived from its definition. */
  @Test void columnsOfViews() {
    run(session, "create table t (id int not null, name varchar(10))",
        "create view v as select id, name from t",
        "create view w (k, doubled) as select id, id * 2 from v",
        "create materialized view mv as select count(*) as c from t");
    assertEquals(List.of("[ID, INTEGER, false, null]",
            "[NAME, VARCHAR(10), true, null]"),
        rows(session, "describe v"));
    assertEquals(List.of("[K, INTEGER, false, null]",
            "[DOUBLED, INTEGER, false, null]"),
        rows(session, "describe w"));
    assertEquals(List.of("[C, BIGINT, false, null]"),
        rows(session, "describe mv"));
    assertEquals(List.of("[DOUBLED, INTEGER, false, null]"),
        rows(session, "show columns in w like 'D%'"));
    assertEquals(List.of("[MV, 1]", "[T, 2]", "[V, 2]", "[W, 2]"),
        rows(session, "select table_name, count(*) from metadata.columns"
            + " group by table_name order by 1"));

    // They follow the definition, and cannot be changed on their own.
    assertThrows(UnsupportedException.class,
        () -> session.execute("insert into metadata.columns (schema_name,"
            + " table_name, column_name, data_type)"
            + " values ('MAIN', 'V', 'X', 'INTEGER')"));
    run(session, "alter table t add column extra boolean",
        "create or replace view v as select * from t");
    assertEquals(3, rows(session, "describe v").size());

    // A view that is no longer valid has no columns; its neighbors do.
    run(session, "drop view v");
    final NotFoundException e =
        assertThrows(NotFoundException.class,
            () -> session.execute("describe w"));
    assertTrue(e.getMessage().contains("no longer valid"), e.getMessage());
    assertEquals(List.of("[MV, 1]", "[T, 3]"),
        rows(session, "select table_name, count(*) from metadata.columns"
            + " group by table_name order by 1"));
  }

  @Test void materializedViews() {
    run(session, "create table t (id int)",
        "insert into t values (1), (2)",
        "create materialized view mv schedule '@daily' as "
            + "select count(*) as c from t");
    assertEquals(List.of("[2]"), rows(session, "select * from mv"));
    assertEquals(List.of("[MV, false, false, null]"),
        rows(session, "show materialized views"));
    // Its schedule is a trigger, of its name, that refreshes it.
    final String schedule = "select trigger_name, schedule, action, paused"
        + " from metadata.triggers";
    assertEquals(
        List.of("[MV, @daily, REFRESH MATERIALIZED VIEW"
            + " \"MEMORY\".\"MAIN\".\"MV\", false]"),
        rows(session, schedule));
    // Which is paused with it,
    run(session, "pause materialized view mv",
        "refresh materialized view mv");
    assertEquals(List.of("[MV, false, true, null]"),
        rows(session, "show materialized views"));
    assertTrue(rows(session, schedule).get(0).endsWith("true]"));
    // fired like any other, which asks for a refresh,
    run(session, "resume materialized view mv", "fire trigger mv");
    assertEquals(
        List.of("[MV, REFRESH MATERIALIZED VIEW"
            + " \"MEMORY\".\"MAIN\".\"MV\"]"),
        rows(session, "select job_name, statement from metadata.jobs"));
    // replaced with it,
    run(session, "create or replace materialized view mv schedule '@hourly'"
        + " as select count(*) as c from t");
    assertTrue(rows(session, schedule).get(0).startsWith("[MV, @hourly,"));
    run(session, "create or replace materialized view mv"
        + " as select count(*) as c from t");
    assertEquals(List.of(), rows(session, schedule));
    // and dropped with it; though not a trigger that merely shares its
    // name.
    run(session, "create or replace materialized view mv schedule '@daily'"
            + " as select count(*) as c from t",
        "create materialized view other as select 1 as c",
        "create trigger other schedule '@daily' as 'something else'",
        "drop materialized view mv", "drop materialized view other");
    assertEquals(List.of("[OTHER, @daily, something else, false]"),
        rows(session, schedule));
    run(session, "create materialized view mv as select count(*) as c from t");
    assertThrows(NotFoundException.class,
        () -> session.execute("refresh materialized view nosuch"));
  }

  /** Statements run inside {@code atomically} are one unit to the
   * catalogs, where each would otherwise be its own. */
  @Test void units() {
    final int[] units = {0};
    final int[] depth = {0};
    final MemoryCatalog catalog = new MemoryCatalog() {
      @Override public <T> T atomically(Supplier<T> work) {
        if (depth[0]++ == 0) {
          units[0]++;
        }
        try {
          return super.atomically(work);
        } finally {
          depth[0]--;
        }
      }
    };
    try (Session s = Monoql.builder().catalog("C", catalog).build()) {
      run(s, "create schema s", "use c.s", "create table t (id int)");
      units[0] = 0;
      run(s, "create materialized view a refresh continuously as"
              + " select id from t",
          "create materialized view b refresh continuously as"
              + " select id from t");
      assertEquals(2, units[0]);

      // A catalog that replans something when its jobs change is told
      // once.
      units[0] = 0;
      s.atomically(() -> {
        run(s, "create materialized view c refresh continuously as"
                + " select id from t",
            "create materialized view d refresh continuously as"
                + " select id from t",
            "insert into t values (1)");
        return null;
      });
      assertEquals(1, units[0]);
      assertEquals(List.of("[A]", "[B]", "[C]", "[D]"),
          rows(s, "select job_name from metadata.jobs where continuous"
              + " order by job_name"));

      // And what is done to rows is undone together.
      assertThrows(IllegalStateException.class, () ->
          s.atomically(() -> {
            run(s, "insert into t values (2)", "insert into t values (3)");
            assertEquals(3, rows(s, "select * from t").size());
            throw new IllegalStateException("changed my mind");
          }));
      assertEquals(List.of("[1]"), rows(s, "select * from t"));
    }
  }

  /** A view that is refreshed continuously is filled by a job, which
   * something else runs, and which goes when the view does. */
  @Test void continuousViews() {
    run(session, "create table t (id int)", "insert into t values (1), (2)",
        "create materialized view live refresh continuously as"
            + " select id from t where id > 1");
    assertEquals(List.of("[LIVE, true]"),
        rows(session, "select view_name, continuous"
            + " from metadata.materialized_views"));
    // Nothing here runs jobs, so nothing has filled it.
    assertEquals(List.of(), rows(session, "select * from live"));
    final List<String> jobs =
        rows(session, "select job_name, statement from metadata.jobs");
    assertEquals(1, jobs.size());
    assertTrue(jobs.get(0).startsWith(
        "[LIVE, INSERT INTO \"MEMORY\".\"MAIN\".\"LIVE\"\nSELECT"), jobs.get(0));
    // What runs the job runs its statement, for ever. Once will do here.
    final Object statement =
        session.execute("select statement from metadata.jobs").iterator()
            .next()[0];
    run(session, (String) statement);
    assertEquals(List.of("[2]"), rows(session, "select * from live"));

    // Pausing the view holds its job, and resuming lets it go; another
    // job of the same name is not the view's, and is left alone.
    run(session, "insert into metadata.jobs (schema_name, job_name,"
        + " submitted_at, statement) values ('MAIN', 'LIVE',"
        + " current_timestamp, 'select 0')");
    final String paused = "select paused from metadata.jobs"
        + " order by statement";
    run(session, "pause materialized view live");
    assertEquals(List.of("[true]", "[false]"), rows(session, paused));
    assertEquals(List.of("[true]"),
        rows(session, "select paused from metadata.materialized_views"));
    run(session, "resume materialized view live");
    assertEquals(List.of("[false]", "[false]"), rows(session, paused));
    run(session, "delete from metadata.jobs where statement = 'select 0'");

    // It is not also refreshed by hand.
    final UnsupportedException e =
        assertThrows(UnsupportedException.class,
            () -> session.execute("refresh materialized view live"));
    assertTrue(e.getMessage().contains("continuously"), e.getMessage());

    // A job of the same name that feeds something else is left alone.
    run(session, "insert into metadata.jobs (schema_name, job_name,"
        + " submitted_at, statement) values ('MAIN', 'LIVE',"
        + " current_timestamp, 'select 1')");
    // Replaced by a view that is not continuous: the job goes, and the
    // view is filled here and now.
    run(session, "create or replace materialized view live as"
        + " select id from t");
    assertEquals(List.of("[select 1]"),
        rows(session, "select statement from metadata.jobs"));
    assertEquals(2, rows(session, "select * from live").size());

    run(session, "create or replace materialized view live refresh"
        + " continuously as select id from t");
    assertEquals(List.of(), rows(session, "select * from live"));
    assertEquals(2, rows(session, "select * from metadata.jobs").size());
    run(session, "drop materialized view live");
    assertEquals(List.of("[select 1]"),
        rows(session, "select statement from metadata.jobs"));
  }

  /** While a view is refreshed, others read the rows it had: never none,
   * and never some. */
  @Test void refreshIsAtomic() {
    final List<Integer> seen = new ArrayList<>();
    final Session[] reader = new Session[1];
    // A table that, whenever it is scanned, has someone else count MV.
    final MemoryCatalog catalog = new MemoryCatalog() {
      @Override public Optional<Table> bind(TableInfo table) {
        if (!table.tableName().equals("PROBE")) {
          return super.bind(table);
        }
        return Optional.of(new Probe(() ->
            seen.add(
                CompletableFuture.supplyAsync(() ->
                    rows(reader[0], "select * from c.s.mv").size()).join())));
      }
    };
    try (Session writer = Monoql.builder().catalog("C", catalog).build()) {
      reader[0] = writer.as(null);
      run(writer, "create schema s", "use c.s", "create table probe (x int)",
          "create table t (id int)", "insert into t values (1), (2), (3)",
          "create materialized view mv as select id from t");
      run(writer, "insert into t values (4)",
          "refresh materialized view mv");
      assertEquals(List.of("[1]", "[2]", "[3]", "[4]"),
          rows(writer, "select * from mv order by id"));
      seen.clear();
      // The refresh has emptied MV, as far as it can tell, when it scans
      // PROBE; the reader still counts four.
      run(writer, "create or replace materialized view mv as"
          + " select id from t union all select x from probe");
      run(writer, "refresh materialized view mv");
      assertEquals(List.of(4, 4), seen);
    }
  }

  /** An empty table that runs something when it is scanned. */
  private static final class Probe extends AbstractTable
      implements ScannableTable {
    private final Runnable onScan;

    Probe(Runnable onScan) {
      this.onScan = onScan;
    }

    @Override public RelDataType getRowType(RelDataTypeFactory typeFactory) {
      return typeFactory.builder().add("X", SqlTypeName.INTEGER).build();
    }

    @Override public Enumerable<Object[]> scan(DataContext root) {
      onScan.run();
      return Linq4j.emptyEnumerable();
    }
  }

  /** The memory catalog keeps the rows of a materialized view: they are
   * what the definition returned at the last refresh. */
  @Test void materializedRows() {
    run(session, "create table t (id int, name varchar)",
        "insert into t values (1, 'a'), (2, 'b')",
        "create materialized view mv as select id, name from t where id > 1");
    // Filled when created.
    assertEquals(List.of("[2, b]"), rows(session, "select * from mv"));
    // Stale until refreshed.
    run(session, "insert into t values (3, 'c')", "delete from t where id = 2");
    assertEquals(List.of("[2, b]"), rows(session, "select * from mv"));
    assertEquals(List.of("[3, c]"),
        rows(session, "select id, name from t where id > 1"));
    run(session, "refresh materialized view mv");
    assertEquals(List.of("[3, c]"), rows(session, "select * from mv"));

    // REFRESH is these steps, and run by hand they do the same.
    assertEquals(
        List.of("DELETE FROM \"MEMORY\".\"MAIN\".\"MV\"",
            "INSERT INTO \"MEMORY\".\"MAIN\".\"MV\""),
        session.lower("refresh materialized view mv").sql().stream()
            .map(sql -> sql.lines().findFirst().orElseThrow()
                .replaceAll(" \\(.*", ""))
            .toList());
    run(session, "insert into t values (4, 'd')");
    for (String sql : session.lower("refresh materialized view mv").sql()) {
      run(session, sql);
    }
    assertEquals(List.of("[3, c]", "[4, d]"),
        rows(session, "select * from mv order by id"));

    // IF NOT EXISTS leaves a view that exists as it is, rows and all.
    run(session, "insert into t values (5, 'e')",
        "create materialized view if not exists mv as select id, name from t");
    assertEquals(2, rows(session, "select * from mv").size());
    run(session,
        "create table if not exists t as select * from (values (0, 'x'))");
    assertEquals(4, rows(session, "select * from t").size());

    // A refresh that fails leaves the rows as they were.
    run(session, "create table u (x int)", "insert into u values (1), (2)",
        "create materialized view q as select 10 / x as y from u");
    assertEquals(List.of("[5]", "[10]"),
        rows(session, "select * from q order by y"));
    run(session, "insert into u values (0)");
    assertThrows(RuntimeException.class,
        () -> session.execute("refresh materialized view q"));
    assertEquals(List.of("[5]", "[10]"),
        rows(session, "select * from q order by y"));

    // Its rows go when it does; and its shape is its definition's.
    run(session, "drop materialized view q",
        "create materialized view q (a, b) as select x, x + 1 from u");
    assertEquals(List.of("[0, 1]", "[1, 2]", "[2, 3]"),
        rows(session, "select a, b from q order by a"));
    run(session, "create or replace materialized view q as"
        + " select count(*) as c from u");
    assertEquals(List.of("[3]"), rows(session, "select c from q"));
  }

  @Test void grants() {
    run(session, "create table t (a int)", "create role analyst",
        "grant select, insert on t to role analyst, user 'bob@example.com' "
            + "with grant option");
    assertEquals(
        List.of("[INSERT, TABLE, MAIN, T, ROLE, ANALYST, true]",
            "[SELECT, TABLE, MAIN, T, ROLE, ANALYST, true]"),
        rows(session, "show grants on t to role analyst"));

    // Granting again is not an error, and can change grantability.
    run(session, "grant select on t to role analyst");
    run(session, "revoke grant option for insert on t from role analyst",
        "revoke insert on t from user 'bob@example.com'");
    assertEquals(
        List.of("[INSERT, ANALYST, false]", "[SELECT, ANALYST, false]",
            "[SELECT, bob@example.com, true]"),
        rows(session, "select privilege, grantee, grantable "
            + "from metadata.grants order by 1, 2"));

    // Revoking what was never granted is not an error either.
    assertEquals(0,
        session.execute("revoke delete on t from role analyst").updateCount());
    run(session, "revoke all on t from role analyst");
    assertEquals(List.of("[SELECT, bob@example.com, true]"),
        rows(session, "select privilege, grantee, grantable "
            + "from metadata.grants"));

    // Levels: catalog, schema, object.
    run(session, "grant usage on catalog memory to analyst",
        "grant usage on schema main to analyst");
    assertEquals(
        List.of("[USAGE, CATALOG, null, null, PRINCIPAL, ANALYST, false]",
            "[USAGE, SCHEMA, MAIN, null, PRINCIPAL, ANALYST, false]"),
        rows(session, "show grants to analyst"));

    // Roles.
    run(session, "grant role analyst to user alice, group eng");
    assertEquals(
        List.of("[MEMORY, ANALYST, USER, ALICE]",
            "[MEMORY, ANALYST, GROUP, ENG]"),
        rows(session, "select * from metadata.role_grants"));
    run(session, "revoke analyst from group eng");
    assertEquals(1, rows(session, "select * from metadata.role_grants").size());
    assertThrows(NotFoundException.class,
        () -> session.execute("grant role nosuch to user alice"));
  }

  /** The other direction of the equivalence: plain DML on the metadata
   * schema does what the statements do. */
  @Test void metadataDml() {
    run(session,
        "insert into metadata.tables (schema_name, table_name, columns) "
            + "values ('MAIN', 'T', array['a int not null', "
            + "'b varchar(10) comment ''bee'''])",
        "insert into t values (1, 'x')");
    assertEquals(List.of("[1, x]"), rows(session, "select * from t"));
    assertEquals(
        List.of("[A, INTEGER, false, null]", "[B, VARCHAR(10), true, bee]"),
        rows(session, "describe t"));

    // ALTER TABLE ... DROP COLUMN, spelled as a delete.
    run(session, "delete from metadata.columns "
        + "where table_name = 'T' and column_name = 'B'");
    assertEquals(List.of("[1]"), rows(session, "select * from t"));

    // GRANT, spelled as an insert; CATALOG_NAME defaults to the current
    // catalog.
    run(session, "insert into metadata.grants (privilege, object_type, "
        + "object_schema, object_name, grantee_type, grantee) "
        + "values ('SELECT', 'TABLE', 'MAIN', 'T', 'ROLE', 'R'), "
        + "('UPDATE', 'TABLE', 'MAIN', 'T', 'ROLE', 'R')");
    assertEquals(
        List.of("[SELECT, TABLE, MAIN, T, ROLE, R, false]",
            "[UPDATE, TABLE, MAIN, T, ROLE, R, false]"),
        rows(session, "show grants on t"));
    assertThrows(AlreadyExistsException.class,
        () -> session.execute("insert into metadata.grants (privilege, "
            + "object_type, object_schema, object_name, grantee_type, "
            + "grantee) values ('SELECT', 'TABLE', 'MAIN', 'T', 'ROLE', "
            + "'R')"));

    // DROP TABLE, spelled as a delete; the rows go with it.
    assertEquals(1,
        session.execute("delete from metadata.tables where table_name = 'T'")
            .updateCount());
    assertThrows(MonoqlException.class,
        () -> session.execute("select * from t"));

    // Metadata joins like any other data.
    run(session, "create table a (x int, y int)", "create table b (x int)");
    assertEquals(List.of("[A, 2]", "[B, 1]"),
        rows(session, "select t.table_name, count(*) "
            + "from metadata.tables t join metadata.columns c "
            + "on t.table_name = c.table_name group by t.table_name "
            + "order by 1"));

    // A bad row is reported by the catalog, not swallowed.
    assertThrows(CatalogException.class,
        () -> session.execute("insert into metadata.tables (schema_name, "
            + "table_name, columns) values ('MAIN', 'BAD', "
            + "array['not a column'])"));
    assertThrows(CatalogException.class,
        () -> session.execute(
            "insert into metadata.schemas (catalog_name) values ('MEMORY')"));
    assertThrows(NotFoundException.class,
        () -> session.execute("insert into metadata.schemas (catalog_name, "
            + "schema_name) values ('NOSUCH', 'S')"));
  }

  @Test void triggers() {
    run(session, "create table t (a int)",
        "create trigger tr after insert or update on t schedule '@hourly' "
            + "with (retries = 3) as insert into t values (9)");
    assertEquals(
        List.of("[TR, T, AFTER, [INSERT, UPDATE], @hourly, false, "
            + "{retries=3}]"),
        rows(session, "select trigger_name, table_name, timing, events, "
            + "schedule, paused, options from metadata.triggers"));
    run(session, "pause trigger tr");
    assertEquals(List.of("[TR, T, @hourly, true, null]"),
        rows(session, "show triggers"));
    // RESUME, spelled as an update.
    run(session, "update metadata.triggers set paused = false "
        + "where trigger_name = 'TR'");
    assertEquals(List.of("[TR, T, @hourly, false, null]"),
        rows(session, "show triggers"));

    // Firing a trigger submits its action as a job, for whatever runs
    // them; nothing here does, so nothing has happened to these.
    run(session, "fire trigger tr with (reason = 'test')", "fire trigger tr");
    assertEquals(List.of("[TR, {reason=test}, null]", "[TR, {}, null]"),
        rows(session, "select job_name, options, state from metadata.jobs"
            + " where statement like 'INSERT INTO \"T\"%'"
            + " order by submitted_at"));
    assertThrows(NotFoundException.class,
        () -> session.execute("fire trigger nosuch"));

    // A job is any statement, submitted by inserting it; so a trigger's
    // action may be to submit one. How it went is not for a statement to
    // say. Deleting it cancels it.
    run(session, "insert into metadata.jobs (schema_name, job_name,"
        + " submitted_at, statement) values ('MAIN', 'nightly',"
        + " current_timestamp, 'refresh materialized view mv')");
    for (String sql : List.of(
        "update metadata.jobs set state = 'DONE' where job_name = 'nightly'",
        "insert into metadata.jobs (schema_name, job_name, submitted_at,"
            + " statement, error) values ('MAIN', 'j', current_timestamp,"
            + " 'select 1', 'oops')")) {
      final CatalogException e =
          assertThrows(CatalogException.class, () -> session.execute(sql));
      assertTrue(e.getMessage().contains("whatever runs them"),
          e.getMessage());
    }
    run(session, "delete from metadata.jobs where job_name = 'nightly'");
    assertEquals(2, rows(session, "select * from metadata.jobs").size());
    run(session, "drop trigger tr");
    assertEquals(List.of(), rows(session, "show triggers"));
  }

  @Test void partitions() {
    run(session, "create table t (a int, ds varchar, region varchar) "
            + "partitioned by (ds, region)",
        "alter table t add partition (ds = '2026-01-01', region = 'us') "
            + "with (location = '/1')",
        "alter table t add partition (ds = '2026-01-02', region = 'us')",
        "alter table t add if not exists partition "
            + "(ds = '2026-01-02', region = 'us')");
    assertEquals(
        List.of("[{DS=2026-01-01, REGION=us}, {location=/1}]",
            "[{DS=2026-01-02, REGION=us}, {}]"),
        rows(session, "show partitions in t"));
    assertThrows(AlreadyExistsException.class,
        () -> session.execute("alter table t add partition "
            + "(ds = '2026-01-02', region = 'us')"));
    assertThrows(CatalogException.class,
        () -> session.execute("alter table t add partition (ds = 'x')"));
    run(session,
        "alter table t drop partition (ds = '2026-01-01', region = 'us')");
    assertEquals(1, rows(session, "show partitions in t").size());
    assertThrows(NotFoundException.class,
        () -> session.execute("alter table t drop partition "
            + "(ds = 'nope', region = 'us')"));
    run(session, "drop table t");
    assertEquals(List.of(), rows(session, "select * from metadata.partitions"));
  }

  @Test void functions() {
    run(session, "create function shout as '" + Shout.class.getName() + "'",
        "create function twice as '" + Shout.class.getName() + "#twice'",
        "create function remote language python as 'def f(): pass'");
    assertEquals(List.of("[ABC!, abcabc]"),
        rows(session, "select shout('abc'), twice('abc')"));
    assertEquals(
        List.of("[REMOTE, PYTHON, null]", "[SHOUT, JAVA, null]",
            "[TWICE, JAVA, null]"),
        rows(session, "show functions"));
    // The engine cannot call a function in a language it does not run.
    assertThrows(MonoqlException.class,
        () -> session.execute("select remote()"));
    run(session, "drop function shout");
    assertThrows(MonoqlException.class,
        () -> session.execute("select shout('abc')"));
  }

  /** A user-defined function. */
  public static class Shout {
    public static String eval(String s) {
      return s.toUpperCase(Locale.ROOT) + "!";
    }

    public static String twice(String s) {
      return s + s;
    }
  }

  @Test void catalogsAndNamespaces() {
    run(session, "create catalog c2 comment 'second' with (type = 'memory')",
        "create schema c2.x", "create table c2.x.y (a int)",
        "insert into c2.x.y values (1)", "create table z (a int)",
        "insert into z values (2)");
    assertEquals(List.of("[C2, memory, second]", "[MEMORY, embedded, null]"),
        rows(session, "show catalogs"));
    // One, two and three part names.
    assertEquals(List.of("[1, 2]"),
        rows(session, "select y.a, z.a from c2.x.y, main.z"));
    assertEquals(List.of("[C2, X, Y]", "[MEMORY, MAIN, Z]"),
        rows(session, "select catalog_name, schema_name, table_name "
            + "from metadata.tables order by 1"));

    run(session, "use c2.x");
    assertEquals("C2", session.currentCatalog());
    assertEquals("X", session.currentSchema());
    assertEquals(List.of("[1]"), rows(session, "select * from y"));
    run(session, "use catalog memory");
    assertNull(session.currentSchema());
    run(session, "use main");
    assertEquals(List.of("[2]"), rows(session, "select * from z"));
    // SET is another spelling of USE, which is an update of SETTINGS.
    run(session, "use c2", "set schema = 'X'");
    assertEquals("X", session.currentSchema());
    assertThrows(NotFoundException.class, () -> session.execute("use nosuch"));
    assertThrows(NotFoundException.class,
        () -> session.execute("set nosuch = 'x'"));

    // From Java, names are taken as they are stored.
    session.use("MEMORY", "MAIN");
    assertEquals(List.of("[2]"), rows(session, "select * from z"));
    session.use("C2", null);
    assertEquals("C2", session.currentCatalog());
    assertNull(session.currentSchema());
    // All or nothing.
    assertThrows(NotFoundException.class,
        () -> session.use("MEMORY", "NOSUCH"));
    assertThrows(NotFoundException.class, () -> session.use("c2", "X"));
    assertEquals("C2", session.currentCatalog());
    assertNull(session.currentSchema());
    assertThrows(CatalogException.class, () -> session.use(null, "X"));
    session.use(null, null);
    assertNull(session.currentCatalog());
    session.use("C2", "X");
    assertEquals(List.of("[1]"), rows(session, "select * from y"));

    // Dropping the current catalog leaves the session with none.
    run(session, "drop catalog c2");
    assertNull(session.currentCatalog());
    assertThrows(CatalogException.class,
        () -> session.execute("create table t (a int)"));
    assertEquals(List.of("[2]"), rows(session, "select * from memory.main.z"));

    assertThrows(CatalogException.class,
        () -> session.execute("create catalog c3 with (type = 'nosuch')"));
    assertThrows(CatalogException.class,
        () -> session.execute("create catalog c3"));
    assertThrows(CatalogException.class,
        () -> session.execute("create catalog metadata "
            + "with (type = 'memory')"));
  }

  @Test void commentsAndOptions() {
    run(session, "create table t (a int) with (k = 'v')",
        "alter table t set (k2 = 'v2', k = 'changed')",
        "comment on table t is 'hello'",
        "comment on column t.a is 'the a'",
        "comment on schema main is 'the schema'");
    assertEquals(List.of("[hello, {k=changed, k2=v2}]"),
        rows(session, "select comment, options from metadata.tables"));
    assertEquals(List.of("[A, INTEGER, true, the a]"),
        rows(session, "describe t"));
    assertEquals(List.of("[MAIN, the schema]"), rows(session, "show schemas"));
    run(session, "comment on table t is null");
    assertEquals(List.of("[T, null]"), rows(session, "show tables"));
  }

  @Test void scriptsAndExplain() {
    final List<String> results = new ArrayList<>();
    session.executeScript("create table t (a int); "
            + "insert into t values (1), (2); select sum(a) from t",
        result -> {
          final List<String> rows = new ArrayList<>();
          result.forEach(row -> rows.add(Arrays.toString(row)));
          results.add(result.isQuery() ? rows.toString()
              : result.updateCount() + " affected");
        });
    assertEquals(List.of("1 affected", "2 affected", "[[3]]"), results);
    assertTrue(session.explain("select * from t where a = 1")
        .contains("EnumerableTableScan(table=[[MEMORY, MAIN, T]])"));
    // Explaining a statement explains what it lowers to.
    assertTrue(session.explain("drop table t")
        .startsWith("MetadataModify(table=[METADATA, TABLES], "
            + "operation=DELETE)"));
    assertEquals(1, rows(session, "explain plan for select * from t").size());
  }

  @Test void errors() {
    final MonoqlException parse =
        assertThrows(MonoqlException.class,
            () -> session.execute("select * frm t"));
    assertTrue(parse.getMessage().contains("line 1, column 14"),
        parse.getMessage());
    final MonoqlException validate =
        assertThrows(MonoqlException.class,
            () -> session.execute("select * from nosuch"));
    assertTrue(validate.getMessage().contains("Object 'NOSUCH' not found"),
        validate.getMessage());
  }
}
