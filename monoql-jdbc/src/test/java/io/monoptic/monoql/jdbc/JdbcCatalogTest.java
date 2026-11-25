/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.jdbc;

import io.monoptic.monoql.Monoql;
import io.monoptic.monoql.Session;
import io.monoptic.monoql.catalog.AlreadyExistsException;
import io.monoptic.monoql.catalog.CatalogException;
import io.monoptic.monoql.catalog.NotFoundException;
import io.monoptic.monoql.catalog.UnsupportedException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests the JDBC catalog against an in-memory HSQLDB database: statements
 * issued in monoql must take effect in the database, and the other way
 * round. */
class JdbcCatalogTest {
  private static final AtomicInteger COUNTER = new AtomicInteger();

  private String url;
  private Connection database;
  private Session session;

  @BeforeEach void open() throws SQLException {
    url = "jdbc:hsqldb:mem:monoql" + COUNTER.incrementAndGet();
    database = DriverManager.getConnection(url, "SA", "");
    try (Statement s = database.createStatement()) {
      s.execute("CREATE SCHEMA SALES");
      s.execute("CREATE TABLE SALES.ORDERS (ID INTEGER NOT NULL PRIMARY KEY, "
          + "CUSTOMER VARCHAR(20), AMOUNT DECIMAL(10, 2))");
      s.execute("INSERT INTO SALES.ORDERS VALUES (1, 'ann', 10.50), "
          + "(2, 'bob', 20.00), (3, 'ann', 5.25)");
    }
    session = Monoql.builder().build();
    session.execute("create catalog db with (type = 'jdbc', url = '" + url
        + "', user = 'SA', password = '')");
    session.execute("use db.sales");
  }

  @AfterEach void close() throws SQLException {
    session.close();
    try (Statement s = database.createStatement()) {
      s.execute("SHUTDOWN");
    }
  }

  private List<String> rows(String sql) {
    final List<String> rows = new ArrayList<>();
    for (Object[] row : session.execute(sql)) {
      rows.add(Arrays.deepToString(row));
    }
    return rows;
  }

  /** Runs a query directly against the database. */
  private List<String> inDatabase(String sql) throws SQLException {
    final List<String> rows = new ArrayList<>();
    try (Statement s = database.createStatement();
         ResultSet r = s.executeQuery(sql)) {
      final int n = r.getMetaData().getColumnCount();
      while (r.next()) {
        final Object[] row = new Object[n];
        for (int i = 0; i < n; i++) {
          row[i] = r.getObject(i + 1);
        }
        rows.add(Arrays.deepToString(row));
      }
    }
    return rows;
  }

  /** Parameters reach the database as parameters. */
  @Test void parameters() throws SQLException {
    final List<String> found = new ArrayList<>();
    for (Object[] row
        : session.execute("select customer from orders where id = ?"
            + " and amount > ?", 2, 1)) {
      found.add(Arrays.deepToString(row));
    }
    assertEquals(List.of("[bob]"), found);
    final String plan =
        session.explain("select customer from orders where id = ?");
    assertTrue(plan.contains("JdbcFilter(condition=[=($0, ?0)])"), plan);
    assertEquals(1,
        session.execute("update orders set customer = ? where id = ?",
            "robert", 2).updateCount());
    assertEquals(List.of("[robert]"),
        inDatabase("SELECT customer FROM sales.orders WHERE id = 2"));
  }

  @Test void readsMetadataAndData() {
    assertTrue(rows("show schemas").contains("[SALES, null]"));
    assertEquals(List.of("[ORDERS, null]"), rows("show tables"));
    assertEquals(
        List.of("[ID, INTEGER, false, null]",
            "[CUSTOMER, VARCHAR(20), true, null]",
            "[AMOUNT, DECIMAL(10, 2), true, null]"),
        rows("describe orders"));
    assertEquals(List.of("[[ID]]"),
        rows("select primary_key from metadata.tables "
            + "where catalog_name = 'DB' and table_name = 'ORDERS'"));
    assertEquals(List.of("[ann, 15.75]", "[bob, 20.00]"),
        rows("select customer, sum(amount) from orders group by customer "
            + "order by 1"));
    // The query is pushed down to the database.
    final String plan =
        session.explain("select customer from orders where id = 2");
    assertTrue(plan.contains("JdbcToEnumerableConverter"), plan);
    assertTrue(plan.contains("JdbcFilter"), plan);
  }

  @Test void writesData() throws SQLException {
    assertEquals(1,
        session.execute("insert into orders values (4, 'cy', 1.00)")
            .updateCount());
    assertEquals(2,
        session.execute("update orders set amount = amount * 2 "
            + "where customer = 'ann'").updateCount());
    assertEquals(1,
        session.execute("delete from orders where id = 2").updateCount());
    assertEquals(List.of("[1, ann, 21.00]", "[3, ann, 10.50]",
            "[4, cy, 1.00]"),
        inDatabase("SELECT * FROM SALES.ORDERS ORDER BY ID"));
  }

  /** What is done in a unit is one transaction in the database: all of
   * it, or none. */
  @Test void unitsAreTransactions() throws SQLException {
    final String all = "SELECT ID FROM SALES.ORDERS ORDER BY ID";
    final JdbcCatalog catalog = new JdbcCatalog(url, null, "SA", "", null);
    try (Session s = Monoql.builder().catalog("DB", catalog).build()) {
      s.execute("use db.sales");
      assertThrows(IllegalStateException.class, () ->
          catalog.atomically(() -> {
            s.execute("delete from orders where id = 1");
            s.execute("insert into orders values (9, 'zed', 1.00)");
            // Inside, it has happened.
            final List<Object> ids = new ArrayList<>();
            for (Object[] row : s.execute("select id from orders")) {
              ids.add(row[0]);
            }
            assertEquals(3, ids.size());
            assertTrue(ids.contains(9) && !ids.contains(1), ids.toString());
            throw new IllegalStateException("changed my mind");
          }));
      assertEquals(List.of("[1]", "[2]", "[3]"), inDatabase(all));

      catalog.atomically(() -> {
        s.execute("delete from orders where id = 1");
        s.execute("insert into orders values (9, 'zed', 1.00)");
        return null;
      });
      assertEquals(List.of("[2]", "[3]", "[9]"), inDatabase(all));
      // Outside a unit, each statement is its own.
      s.execute("delete from orders where id = 9");
      assertEquals(List.of("[2]", "[3]"), inDatabase(all));
    }
  }

  @Test void ddlReachesTheDatabase() throws SQLException {
    session.execute("create schema staging");
    session.execute("create table staging.events (id int not null primary "
        + "key, body varchar(100), ts timestamp)");
    session.execute("insert into staging.events (id, body) values (1, 'x')");
    assertEquals(List.of("[1, x, null]"),
        inDatabase("SELECT * FROM STAGING.EVENTS"));

    session.execute("alter table staging.events add column score double");
    session.execute("alter table staging.events drop column ts");
    session.execute("alter table staging.events rename to log");
    assertEquals(
        List.of("[ID, INTEGER, false, null]", "[BODY, VARCHAR(100), true, null]",
            "[SCORE, DOUBLE, true, null]"),
        rows("describe staging.log"));
    assertEquals(List.of("[1, x, null]"),
        inDatabase("SELECT * FROM STAGING.LOG"));

    // And the other spelling: DML on the metadata schema is DDL in the
    // database.
    session.execute("insert into metadata.tables (catalog_name, schema_name, "
        + "table_name, columns) values ('DB', 'STAGING', 'HAND', "
        + "array['a int not null', 'b varchar(5)'])");
    assertEquals(List.of(), inDatabase("SELECT A, B FROM STAGING.HAND"));
    session.execute("delete from metadata.tables where catalog_name = 'DB' "
        + "and table_name = 'HAND'");
    assertThrows(SQLException.class,
        () -> inDatabase("SELECT * FROM STAGING.HAND"));

    session.execute("drop table staging.log");
    session.execute("drop schema staging");
    assertFalse(rows("show schemas").contains("[STAGING, null]"));

    assertThrows(AlreadyExistsException.class,
        () -> session.execute("create table orders (a int)"));
    assertEquals(0,
        session.execute("create table if not exists orders (a int)")
            .updateCount());
    assertThrows(NotFoundException.class,
        () -> session.execute("drop table nosuch"));
  }

  @Test void createTableAs() throws SQLException {
    session.execute("create table big_spenders as "
        + "select customer, sum(amount) as total from orders "
        + "group by customer having sum(amount) > 15");
    assertEquals(List.of("[ann, 15.75]", "[bob, 20.00]"),
        inDatabase("SELECT * FROM SALES.BIG_SPENDERS ORDER BY 1"));
  }

  @Test void grantsReachTheDatabase() throws SQLException {
    session.execute("create role analyst");
    session.execute("grant select, insert on orders to role analyst");
    assertEquals(List.of("[INSERT]", "[SELECT]"),
        inDatabase("SELECT PRIVILEGE_TYPE "
            + "FROM INFORMATION_SCHEMA.TABLE_PRIVILEGES "
            + "WHERE GRANTEE = 'ANALYST' AND TABLE_NAME = 'ORDERS' "
            + "ORDER BY 1"));
    // Grants made in the database are visible here. JDBC cannot say what
    // kind of principal a grantee is.
    assertEquals(
        List.of("[INSERT, TABLE, SALES, ORDERS, PRINCIPAL, ANALYST, false,"
                + " null, null, null]",
            "[SELECT, TABLE, SALES, ORDERS, PRINCIPAL, ANALYST, false,"
                + " null, null, null]"),
        rows("show grants on orders to role analyst"));
    session.execute("revoke insert on orders from role analyst");
    assertEquals(List.of("[SELECT]"),
        inDatabase("SELECT PRIVILEGE_TYPE "
            + "FROM INFORMATION_SCHEMA.TABLE_PRIVILEGES "
            + "WHERE GRANTEE = 'ANALYST' AND TABLE_NAME = 'ORDERS'"));
    session.execute("drop role analyst");
  }

  /** What JDBC cannot express is unsupported, and says so. */
  @Test void theRestIsUnsupported() {
    for (String sql
        : List.of("create view v as select * from orders",
            "create trigger tr on orders as 'x'",
            "create user u",
            "grant usage on schema sales to role r",
            "grant insert on orders using schema sales to role r",
            "grant role r to user u",
            "create table t (a int) with (k = 'v')",
            "create table t (a int) comment 'c'",
            "create table t (a int, b int) partitioned by (b)",
            "comment on table orders is 'c'",
            "alter table orders set (k = 'v')",
            "alter table orders add partition (id = 1)",
            "create schema s2 comment 'c'",
            "show roles")) {
      assertThrows(UnsupportedException.class, () -> session.execute(sql),
          sql);
    }
    assertEquals(List.of(), rows("select * from metadata.views"));
  }

  @Test void badCatalogs() {
    assertThrows(CatalogException.class,
        () -> session.execute("create catalog x with (type = 'jdbc')"));
    assertThrows(CatalogException.class,
        () -> session.execute("create catalog x with (type = 'jdbc', "
            + "url = 'jdbc:nosuch:x')"));
    assertThrows(CatalogException.class,
        () -> session.execute("create catalog x with (type = 'jdbc', "
            + "url = '" + url + "', nonsense = '1')"));
    assertEquals(List.of("[DB, jdbc]", "[MEMORY, embedded]"),
        rows("select catalog_name, type from metadata.catalogs order by 1"));
  }

  /** Joins across catalogs: a JDBC table and an in-memory one. */
  @Test void federation() {
    session.execute("create table memory.main.vip (name varchar(20))");
    session.execute("insert into memory.main.vip values ('ann')");
    assertEquals(List.of("[1, ann]", "[3, ann]"),
        rows("select o.id, v.name from orders o "
            + "join memory.main.vip v on o.customer = v.name order by 1"));
  }
}
