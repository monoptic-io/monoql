/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql;

import io.monoptic.monoql.catalog.AlreadyExistsException;
import io.monoptic.monoql.catalog.CatalogException;
import io.monoptic.monoql.catalog.NotFoundException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

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
    assertEquals(List.of("[catalog, MEMORY]", "[schema, MAIN]"),
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

  @Test void materializedViews() {
    run(session, "create table t (id int)",
        "insert into t values (1), (2)",
        "create materialized view mv schedule '@daily' as "
            + "select count(*) as c from t");
    assertEquals(List.of("[2]"), rows(session, "select * from mv"));
    assertEquals(List.of("[MV, @daily, false, null]"),
        rows(session, "show materialized views"));
    run(session, "pause materialized view mv",
        "refresh materialized view mv");
    assertEquals(List.of("[MV, @daily, true, null]"),
        rows(session, "show materialized views"));
    assertEquals(List.of("[MAIN, MV]"),
        rows(session, "select schema_name, view_name from metadata.refreshes"));
    assertThrows(NotFoundException.class,
        () -> session.execute("refresh materialized view nosuch"));
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

    run(session, "fire trigger tr with (reason = 'test')", "fire trigger tr");
    assertEquals(List.of("[TR, {reason=test}]", "[TR, {}]"),
        rows(session, "select trigger_name, options from metadata.firings"));
    assertThrows(NotFoundException.class,
        () -> session.execute("fire trigger nosuch"));
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
