/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql;

import io.monoptic.monoql.catalog.AccessDeniedException;
import io.monoptic.monoql.catalog.DataTypes;

import org.apache.calcite.rel.type.RelDataType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static io.monoptic.monoql.Sessions.rows;
import static io.monoptic.monoql.Sessions.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests preparing statements, and giving them parameters. */
class PreparedTest {
  private Session session;

  @BeforeEach void open() {
    session = Monoql.builder().build();
    run(session, "create table t (id int not null, name varchar(10),"
        + " amount decimal(10, 2), d date, ts timestamp)",
        "insert into t values (1, 'a', 1.50, date '2026-01-02',"
            + " timestamp '2026-01-02 03:04:05'), (2, 'b', 2.50, null, null)");
  }

  @AfterEach void close() {
    session.close();
  }

  private static List<String> types(RelDataType rowType) {
    final List<String> types = new ArrayList<>();
    rowType.getFieldList().forEach(f ->
        types.add(f.getName() + " " + DataTypes.format(f.getType())));
    return types;
  }

  private static List<String> read(Result result) {
    final List<String> rows = new ArrayList<>();
    result.forEach(row -> rows.add(Arrays.deepToString(row)));
    return rows;
  }

  /** Preparing says what a statement returns and takes, and runs
   * nothing. */
  @Test void shape() {
    final Prepared query =
        session.prepare("select id, name, amount * 2 as twice from t"
            + " where id > ? and name = ?");
    assertTrue(query.isQuery());
    assertEquals(List.of("ID", "NAME", "TWICE"), query.columnNames());
    assertEquals(
        List.of("ID INTEGER", "NAME VARCHAR(10)", "TWICE DECIMAL(19, 2)"),
        types(query.rowType()));
    assertEquals(2, query.parameterCount());
    assertEquals(List.of("?0 INTEGER", "?1 VARCHAR(10)"),
        types(query.parameterType()));

    final Prepared insert = session.prepare("insert into t (id, d) values (?, ?)");
    assertFalse(insert.isQuery());
    assertNull(insert.rowType());
    assertEquals(List.of("?0 INTEGER", "?1 DATE"),
        types(insert.parameterType()));
    assertEquals(2, rows(session, "select * from t").size());

    // Statements that stand for DML, or for a query, can be prepared too.
    final Prepared create = session.prepare("create table u (id int)");
    assertFalse(create.isQuery());
    assertEquals(0, create.parameterCount());
    assertEquals(List.of(), rows(session, "show tables like 'U'"));
    final Prepared describe = session.prepare("describe t");
    assertEquals(List.of("COLUMN_NAME", "DATA_TYPE", "NULLABLE", "COMMENT"),
        describe.columnNames());
    assertEquals(5, read(describe.execute()).size());
    assertEquals(List.of("PLAN"),
        session.prepare("explain plan for select * from t").columnNames());

    // What is wrong with a statement is found when it is prepared.
    assertThrows(MonoqlException.class,
        () -> session.prepare("select nosuch from t"));
    assertThrows(MonoqlException.class, () -> session.prepare("select ?"));
  }

  @Test void parameters() {
    final Prepared query =
        session.prepare("select name from t where id = ?");
    assertEquals(List.of("[a]"), read(query.execute(1)));
    assertEquals(List.of("[b]"), read(query.execute(2)));
    // A number is a number; a null matches nothing.
    assertEquals(List.of("[b]"), read(query.execute(2L)));
    assertEquals(List.of(), read(query.execute((Object) null)));

    // A value is a value, whatever it looks like.
    assertEquals(List.of(),
        read(session.execute("select * from t where name = ?",
            "a' or '1' = '1")));

    // Values go in as they come out.
    session.execute("insert into t values (?, ?, ?, ?, ?)", 3, "c",
        new BigDecimal("3.25"), LocalDate.of(2026, 3, 4),
        LocalDateTime.of(2026, 3, 4, 5, 6, 7));
    assertEquals(List.of("[3, c, 3.25, 2026-03-04, 2026-03-04T05:06:07]"),
        read(session.execute("select * from t where d = ? and ts = ?",
            LocalDate.of(2026, 3, 4), LocalDateTime.of(2026, 3, 4, 5, 6, 7))));
    assertEquals(List.of("[14:15:16, 2026-01-01T00:00:00Z]"),
        read(session.execute("select cast(? as time),"
                + " cast(? as timestamp with local time zone)",
            LocalTime.of(14, 15, 16), Instant.parse("2026-01-01T00:00:00Z"))));

    assertEquals(2,
        session.execute("update t set amount = amount + ? where id < ?", 10, 3)
            .updateCount());
    assertEquals(List.of("[11.50]", "[12.50]", "[3.25]"),
        rows(session, "select amount from t order by id"));
    assertEquals(1,
        session.execute("delete from t where id = ?", 3).updateCount());
  }

  @Test void wrongParameters() {
    final Prepared query = session.prepare("select name from t where id = ?");
    assertEquals("The statement has 1 parameter, but 0 values were given",
        assertThrows(MonoqlException.class, query::execute).getMessage());
    assertEquals("The statement has 1 parameter, but 2 values were given",
        assertThrows(MonoqlException.class, () -> query.execute(1, 2))
            .getMessage());
    assertEquals("Parameter 1 is INTEGER; it cannot be given a String",
        assertThrows(MonoqlException.class, () -> query.execute("one"))
            .getMessage());
    assertEquals("The statement has 0 parameters, but 1 value was given",
        assertThrows(MonoqlException.class,
            () -> session.execute("select * from t", 1)).getMessage());
    // Only queries and DML take parameters.
    assertTrue(
        assertThrows(MonoqlException.class,
            () -> session.execute("comment on table t is 'x'", 1))
            .getMessage().startsWith("Parameters can be given to queries"));
    assertThrows(MonoqlException.class,
        () -> session.execute("create table u as select * from t"
            + " where id = ?"));
  }

  /** Metadata is data: its DML takes parameters like any other. */
  @Test void metadata() {
    session.execute("insert into metadata.grants (privilege, object_type,"
        + " object_schema, object_name, grantee_type, grantee, grantable)"
        + " values (?, 'TABLE', 'MAIN', 'T', 'USER', ?, false)",
        "SELECT", "ANN");
    assertEquals(List.of("[SELECT]"),
        read(session.execute("select privilege from metadata.grants"
            + " where grantee = ?", "ANN")));
  }

  /** A statement is prepared as it stands; each execution answers for
   * itself. */
  @Test void preparedIsNotPlanned() {
    final Prepared query = session.prepare("select count(*) from t");
    run(session, "insert into t (id) values (9)");
    assertEquals(List.of("[3]"), read(query.execute()));
    // Preparing takes no privilege that describing would not; running
    // does.
    try (Session ann = session.as(Identity.of("ANN"))) {
      final Prepared hers = ann.prepare("select * from t where id = ?");
      assertEquals(1, hers.parameterCount());
      assertThrows(AccessDeniedException.class, () -> hers.execute(1));
    }
    run(session, "drop table t");
    assertThrows(MonoqlException.class, query::execute);
  }
}
