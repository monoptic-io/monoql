/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.metastore;

import io.monoptic.monoql.Identity;
import io.monoptic.monoql.Monoql;
import io.monoptic.monoql.MonoqlException;
import io.monoptic.monoql.Session;
import io.monoptic.monoql.catalog.AccessDeniedException;
import io.monoptic.monoql.catalog.AlreadyExistsException;
import io.monoptic.monoql.catalog.Catalog;
import io.monoptic.monoql.catalog.CatalogException;
import io.monoptic.monoql.catalog.NotFoundException;
import io.monoptic.monoql.catalog.UnsupportedException;
import io.monoptic.monoql.catalog.SchemaInfo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests of the metastore catalog, through sessions, against a database.
 *
 * <p>The database is H2, speaking PostgreSQL's dialect and with
 * PostgreSQL's kind of concurrency; or a real PostgreSQL, if the system
 * property {@code monoql.test.metastore.url} names one (with
 * {@code .user} and {@code .password}). */
class MetastoreTest {
  private static final AtomicInteger COUNTER = new AtomicInteger();

  private final List<Session> sessions = new ArrayList<>();
  /** Tenants are named afresh for each test, so that tests can share a
   * database. */
  private final String run =
      "t" + System.nanoTime() + "_" + COUNTER.incrementAndGet() + "_";
  private final String metastore =
      System.getProperty("monoql.test.metastore.url",
          "jdbc:h2:mem:" + run + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
              + ";LOCK_TIMEOUT=60000");

  @AfterEach void close() {
    sessions.forEach(Session::close);
  }

  private Map<String, String> options(String tenant) {
    final Map<String, String> options = new LinkedHashMap<>();
    options.put("url", metastore);
    options.put("tenant", run + tenant);
    final String user = System.getProperty("monoql.test.metastore.user");
    if (user != null) {
      options.put("user", user);
      options.put("password",
          System.getProperty("monoql.test.metastore.password"));
    }
    return options;
  }

  /** A session over a catalog called LAKE, of a tenant; like a server
   * that has just started. */
  private Session session(String tenant, String... more) {
    final Map<String, String> options = options(tenant);
    for (int i = 0; i < more.length; i += 2) {
      options.put(more[i], more[i + 1]);
    }
    final Catalog catalog =
        new MetastoreCatalogProvider().create("LAKE", options);
    final Session session = Monoql.builder().catalog("LAKE", catalog).build();
    sessions.add(session);
    return session;
  }

  private static List<String> rows(Session session, String sql) {
    final List<String> rows = new ArrayList<>();
    for (Object[] row : session.execute(sql)) {
      rows.add(Arrays.deepToString(row));
    }
    return rows;
  }

  private static void run(Session session, String... statements) {
    for (String sql : statements) {
      session.execute(sql);
    }
  }

  private static final List<String> TABLES =
      List.of("schemas", "tables", "columns", "views", "materialized_views",
          "partitions", "functions", "triggers", "jobs",
          "roles", "users", "grants", "role_grants");

  private static Map<String, List<String>> dump(Session session) {
    final Map<String, List<String>> dump = new LinkedHashMap<>();
    for (String table : TABLES) {
      final List<String> rows =
          rows(session, "select * from metadata." + table);
      Collections.sort(rows);
      dump.put(table, rows);
    }
    return dump;
  }

  /** Everything there is, written by one session, is there for the next:
   * every kind of row survives the trip to the database and back. */
  @Test void everythingIsKept() {
    final Session first = session("acme");
    run(first, "create schema s comment 'a schema' with (k = 'v')",
        "use s",
        "create table t (id int not null primary key, name varchar(20)"
            + " comment 'who', tags varchar array, born date,"
            + " amount decimal(10, 2)) partitioned by (born)"
            + " comment 'a table' with (retention = '7d')",
        "alter table t add partition (born = '2026-01-01') with (hot = true)",
        "alter table t add column age int",
        "create view v (a, b) comment 'a view' as select id, name from t",
        "create materialized view mv schedule '0 * * * *' as select id from t",
        "pause materialized view mv",
        "create function f language java comment 'f' as 'com.example.F'",
        "create trigger tr after insert or update on t as 'notify'",
        "fire trigger tr with (reason = 'test')",
        "create role analyst comment 'reads'",
        "create user ann with (email = 'ann@example.com')",
        "grant select, insert on t to role analyst with grant option",
        "grant all on schema s to user ann",
        "grant create table on catalog lake to group staff",
        "grant role analyst to user ann",
        "comment on column t.name is 'whom'");
    // With no data catalog, a materialized view reads as its definition.
    assertTrue(
        assertThrows(UnsupportedException.class,
            () -> first.execute("refresh materialized view mv")).getMessage()
            .contains("nothing to refresh"));
    final Map<String, List<String>> written = dump(first);
    for (String table : TABLES) {
      assertTrue(!written.get(table).isEmpty(), table);
    }
    final Session second = session("acme");
    assertEquals(written, dump(second));
    assertEquals(List.of("[ID, INTEGER, false, null]",
            "[NAME, VARCHAR(20), true, whom]", "[TAGS, VARCHAR ARRAY, true, null]",
            "[BORN, DATE, true, null]", "[AMOUNT, DECIMAL(10, 2), true, null]",
            "[AGE, INTEGER, true, null]"),
        rows(second, "describe s.t"));

    // And what one changes, the other sees: there is nothing in between.
    run(second, "use s", "alter table t rename to u", "drop view v",
        "revoke insert on u from role analyst", "resume materialized view mv");
    assertEquals(dump(second), dump(first));
    assertEquals(List.of("[U, a table]"), rows(first, "show tables in s"));
    // The grants followed the table to its new name.
    assertEquals(List.of("[SELECT, U]"),
        rows(first, "select privilege, object_name from metadata.grants"
            + " where grantee = 'ANALYST'"));
  }

  /** The rules are those of any standard catalog. */
  @Test void rules() {
    final Session s = session("acme");
    assertThrows(NotFoundException.class,
        () -> s.execute("create table lake.nosuch.t (a int)"));
    run(s, "create schema s", "use s", "create table t (a int)");
    assertThrows(AlreadyExistsException.class,
        () -> s.execute("create table t (a int)"));
    assertThrows(AlreadyExistsException.class,
        () -> s.execute("create view t as select 1 as a"));
    assertThrows(CatalogException.class, () -> s.execute("drop schema s"));
    run(s, "create table if not exists t (a int)", "drop table t",
        "drop schema s");
    assertEquals(List.of(), rows(s, "show schemas in lake"));
  }

  /** Two tenants in one database have nothing to do with each other; nor
   * do two catalogs of one tenant. */
  @Test void tenants() {
    final Session acme = session("acme");
    final Session globex = session("globex");
    run(acme, "create schema s", "create table s.orders (a int)",
        "grant select on s.orders to user ann");
    assertEquals(List.of(), rows(globex, "show schemas in lake"));
    run(globex, "create schema s", "create table s.orders (b varchar)",
        "create table s.more (c int)");
    assertEquals(List.of("[A, INTEGER, true, null]"),
        rows(acme, "describe lake.s.orders"));
    assertEquals(List.of("[ORDERS]"),
        rows(acme, "select table_name from metadata.tables"));
    assertEquals(List.of("[0]"),
        rows(globex, "select count(*) from metadata.grants"));

    final Session both = session("acme");
    final Map<String, String> other = options("acme");
    both.execute("create catalog other with (type = 'metastore', url = '"
        + other.get("url") + "', tenant = '" + other.get("tenant") + "'"
        + (other.get("user") == null ? ""
            : ", user = '" + other.get("user") + "', password = '"
                + other.get("password") + "'")
        + ")");
    assertEquals(List.of("[LAKE, S]"),
        rows(both, "select catalog_name, schema_name from metadata.schemas"));
    run(both, "create schema other.x");
    assertEquals(List.of("[S]"),
        rows(acme, "select schema_name from metadata.schemas"));
  }

  /** A statement that is several changes happens entirely or not at
   * all. */
  @Test void statementsAreAtomic() {
    final Session s = session("acme");
    run(s, "create schema s", "use s", "create table t (a int)",
        "grant select on t to user ann", "grant insert on t to user bob");

    // Two rows, of which the second cannot be: the first is not kept.
    assertThrows(AlreadyExistsException.class,
        () -> s.execute("insert into metadata.tables (schema_name,"
            + " table_name, columns) values ('S', 'NEW', array['a INT']),"
            + " ('S', 'T', array['a INT'])"));
    assertEquals(List.of("[T]"),
        rows(s, "select table_name from metadata.tables"));

    // Creating a table from a query is the table, and then its rows; with
    // nowhere to put rows, there is no table either.
    assertThrows(RuntimeException.class,
        () -> s.execute("create table copy as select * from (values (1)) as"
            + " v (a)"));
    assertEquals(List.of("[T]"),
        rows(s, "select table_name from metadata.tables"));

    // Dropping a table is the table, and the grants on it. One who may
    // not drop it leaves both.
    final Session ann = s.as(Identity.of("ANN"));
    sessions.add(ann);
    assertThrows(AccessDeniedException.class,
        () -> ann.execute("drop table lake.s.t"));
    assertEquals(List.of("[2]"),
        rows(s, "select count(*) from metadata.grants"));
    run(s, "drop table t");
    assertEquals(List.of("[0]"),
        rows(s, "select count(*) from metadata.grants"));
    assertEquals(List.of("[0]"),
        rows(session("acme"), "select count(*) from metadata.tables"));
  }

  /** A change is a change to a row as it was read. */
  @Test void staleChangesAreRefused() {
    final MetastoreCatalogProvider provider = new MetastoreCatalogProvider();
    final Catalog one = provider.create("LAKE", options("acme"));
    final Catalog two = provider.create("LAKE", options("acme"));
    try (one; two) {
      final SchemaInfo created = new SchemaInfo("S", null, Map.of());
      one.schemas().insert(created);
      final SchemaInfo commented = new SchemaInfo("S", "theirs", Map.of());
      two.schemas().update(created, commented);
      final CatalogException e =
          assertThrows(CatalogException.class,
              () -> one.schemas().update(created,
                  new SchemaInfo("S", "mine", Map.of())));
      assertTrue(e.getMessage().contains("someone else"), e.getMessage());
      assertThrows(CatalogException.class, () -> one.schemas().delete(created));
      assertEquals(List.of(commented), one.schemas().scan());
      one.schemas().delete(commented);
    }
  }

  /** A tenant that has been removed from the metastore is gone, and a
   * server that still has its catalog open cannot bring it back. */
  @Test void removingATenant() throws Exception {
    final Session acme = session("acme");
    final Session globex = session("globex");
    run(acme, "create schema s", "create table s.t (a int)");
    run(globex, "create schema s");
    final Map<String, String> o = options("acme");
    try (Connection c =
            DriverManager.getConnection(o.get("url"), o.get("user"),
                o.get("password"));
        java.sql.PreparedStatement delete =
            c.prepareStatement("delete from monoql_tenants where tenant_id = ?")) {
      delete.setString(1, o.get("tenant"));
      assertEquals(1, delete.executeUpdate());
    }
    assertEquals(List.of(), rows(acme, "select * from metadata.tables"));
    assertThrows(NotFoundException.class,
        () -> acme.execute("create schema lake.again"));
    assertEquals(List.of("[S]"),
        rows(globex, "select schema_name from metadata.schemas"));
  }

  /** Several servers, one metastore: what each checks before it writes is
   * still true when it writes. */
  @Test void writersTakeTurns() throws Exception {
    final int servers = 6;
    final List<Session> sessions = new ArrayList<>();
    for (int i = 0; i < servers; i++) {
      sessions.add(session("acme"));
    }
    run(sessions.get(0), "create schema s", "create schema doomed");

    // Everyone creates the same table: one of them does.
    final AtomicInteger created = new AtomicInteger();
    race(servers, i -> {
      try {
        sessions.get(i).execute("create table lake.s.t (a int)");
        created.incrementAndGet();
      } catch (AlreadyExistsException e) {
        // Someone else did.
      }
    });
    assertEquals(1, created.get());

    // Everyone makes grants of their own, and tables of their own: none
    // is lost.
    race(servers, i -> {
      for (int n = 0; n < 5; n++) {
        run(sessions.get(i),
            "grant select on lake.s.t to user u" + i + "_" + n,
            "create table lake.s.t" + i + "_" + n + " (a int)");
      }
    });
    assertEquals(List.of("[" + servers * 5 + "]"),
        rows(sessions.get(0), "select count(*) from metadata.grants"));
    assertEquals(List.of("[" + (servers * 5 + 1) + "]"),
        rows(sessions.get(0), "select count(*) from metadata.tables"));

    // Some put tables in a schema while another drops it. Either the
    // schema went first, and no table got in; or a table got in first,
    // and the schema stayed. Never a table in a schema that is not there.
    race(servers, i -> {
      try {
        if (i == 0) {
          sessions.get(i).execute("drop schema lake.doomed");
        } else {
          sessions.get(i).execute("create table lake.doomed.t" + i + " (a int)");
        }
      } catch (CatalogException e) {
        // Not found, or not empty.
      }
    });
    final List<String> schema =
        rows(sessions.get(1), "select schema_name from metadata.schemas"
            + " where schema_name = 'DOOMED'");
    final List<String> tables =
        rows(sessions.get(1), "select count(*) from metadata.tables"
            + " where schema_name = 'DOOMED'");
    assertTrue(schema.isEmpty() == tables.equals(List.of("[0]")),
        schema + " " + tables);
  }

  /** Something each of several threads does, all at once. */
  private interface Racer {
    void run(int i) throws Exception;
  }

  private static void race(int n, Racer racer) throws Exception {
    final CountDownLatch start = new CountDownLatch(1);
    final List<Throwable> failures =
        Collections.synchronizedList(new ArrayList<>());
    final List<Thread> threads = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      final int index = i;
      final Thread thread = new Thread(() -> {
        try {
          start.await();
          racer.run(index);
        } catch (Throwable e) {
          failures.add(e);
        }
      });
      threads.add(thread);
      thread.start();
    }
    start.countDown();
    for (Thread thread : threads) {
      thread.join();
    }
    assertEquals(List.of(), failures);
  }

  /** With a data catalog, tables are real: here, in another database. */
  @Test void dataCatalog() throws Exception {
    final String data = "jdbc:hsqldb:mem:" + run + "data";
    final String[] options = {"data.type", "jdbc", "data.url", data};
    final Session s = session("acme", options);
    run(s, "create schema s", "use s",
        "create table orders (id int not null primary key, customer"
            + " varchar(20) comment 'who', amount decimal(10, 2))"
            + " comment 'what was bought'",
        "insert into orders values (1, 'ann', 10.50), (2, 'bob', 20.00)",
        "create view big as select * from orders where amount > 15");
    assertEquals(List.of("[2, bob, 20.00]"), rows(s, "select * from big"));
    // The table is in the other database, and the comments are not.
    try (Connection c = DriverManager.getConnection(data);
        Statement statement = c.createStatement();
        java.sql.ResultSet r =
            statement.executeQuery("select count(*) from s.orders")) {
      assertTrue(r.next());
      assertEquals(2, r.getInt(1));
    }
    assertEquals(List.of("[ORDERS, what was bought]"), rows(s, "show tables"));

    // Another server finds the table, its rows, and the view of them.
    final Session again = session("acme", options);
    run(again, "use s", "alter table orders add column note varchar(10)",
        "update orders set note = 'late' where id = 2");
    // (A view has the columns it had when it was defined.)
    assertEquals(List.of("[2, bob, 20.00]"), rows(again, "select * from big"));
    assertEquals(List.of("[1, ann, 10.50, null]", "[2, bob, 20.00, late]"),
        rows(s, "select * from orders order by id"));

    // What the data catalog will not do is not done here either.
    assertThrows(RuntimeException.class,
        () -> again.execute("create table s.t (a geometry)"));
    assertEquals(List.of("[ORDERS]"),
        rows(again, "select table_name from metadata.tables"));

    run(again, "drop view big", "drop table orders", "drop schema s");
    try (Connection c = DriverManager.getConnection(data);
        Statement statement = c.createStatement()) {
      assertThrows(java.sql.SQLException.class,
          () -> statement.executeQuery("select count(*) from s.orders"));
    }
  }

  /** The rows of a materialized view are in the data catalog, in a table
   * of its name. */
  @Test void materializedRows() throws Exception {
    final String data = "jdbc:hsqldb:mem:" + run + "mv";
    final String[] options = {"data.type", "jdbc", "data.url", data};
    final Session s = session("acme", options);
    run(s, "create schema s", "use s",
        "create table orders (id int not null, region varchar(10),"
            + " amount int)",
        "insert into orders values (1, 'east', 10), (2, 'east', 5),"
            + " (3, 'west', 7)",
        "create materialized view totals as select region,"
            + " sum(amount) as total from orders group by region");
    assertEquals(List.of("[east, 15]", "[west, 7]"),
        rows(s, "select * from totals order by region"));
    assertEquals(2, count(data, "s.totals"));

    // Stale until refreshed; and another server reads the same rows.
    run(s, "insert into orders values (4, 'west', 100)");
    final Session again = session("acme", options);
    assertEquals(List.of("[east, 15]", "[west, 7]"),
        rows(again, "select * from s.totals order by region"));
    run(again, "refresh materialized view s.totals");
    assertEquals(List.of("[east, 15]", "[west, 107]"),
        rows(s, "select * from totals order by region"));

    // A refresh is one transaction in the data catalog: if its query
    // fails, the rows it had deleted are back.
    run(s, "create materialized view shares as"
        + " select id, 100 / amount as share from orders");
    assertEquals(4, count(data, "s.shares"));
    run(s, "insert into orders values (5, 'north', 0)");
    assertThrows(RuntimeException.class,
        () -> again.execute("refresh materialized view s.shares"));
    assertEquals(4, count(data, "s.shares"));
    run(s, "delete from orders where id = 5",
        "refresh materialized view shares", "drop materialized view shares");

    // Another query is other rows, of another shape.
    run(s, "create or replace materialized view totals as"
        + " select count(*) as orders from orders");
    assertEquals(List.of("[4]"), rows(again, "select * from s.totals"));
    assertEquals(1, count(data, "s.totals"));

    run(s, "drop materialized view totals");
    assertThrows(java.sql.SQLException.class, () -> count(data, "s.totals"));
    run(s, "drop table orders", "drop schema s");
  }

  private static int count(String url, String table) throws Exception {
    try (Connection c = DriverManager.getConnection(url);
        Statement statement = c.createStatement();
        java.sql.ResultSet r =
            statement.executeQuery("select count(*) from " + table)) {
      assertTrue(r.next());
      return r.getInt(1);
    }
  }

  @Test void options() {
    final MetastoreCatalogProvider provider = new MetastoreCatalogProvider();
    assertThrows(CatalogException.class,
        () -> provider.create("C", Map.of()));
    CatalogException e =
        assertThrows(CatalogException.class,
            () -> provider.create("C", Map.of("url", metastore, "colour", "red")));
    assertTrue(e.getMessage().contains("colour"), e.getMessage());
    e = assertThrows(CatalogException.class,
        () -> provider.create("C",
            Map.of("url", metastore, "data.type", "nosuch")));
    assertTrue(e.getMessage().contains("nosuch"), e.getMessage());
    e = assertThrows(CatalogException.class,
        () -> provider.create("C", Map.of("url", metastore, "data.url", "x")));
    assertTrue(e.getMessage().contains("data.type"), e.getMessage());
    // Without its tables, and told not to make them, it cannot start.
    assertThrows(CatalogException.class,
        () -> provider.create("C",
            Map.of("url", "jdbc:h2:mem:" + run + "empty", "create",
                "false")));
    assertThrows(MonoqlException.class,
        () -> session("acme").execute("select * from lake.nosuch.t"));
  }
}
