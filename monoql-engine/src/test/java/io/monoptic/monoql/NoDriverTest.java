/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql;

import io.monoptic.monoql.calcite.LegacyJdbc;

import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.tools.FrameworkConfig;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.Planner;
import org.apache.calcite.tools.RelBuilder;

import org.junit.jupiter.api.Test;

import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Collections;

import static io.monoptic.monoql.Sessions.rows;
import static io.monoptic.monoql.Sessions.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests that monoql, and the parts of Calcite it stands on, work with no
 * Calcite JDBC driver in existence.
 *
 * <p>The class path of this module is the proof: it has core, but not the
 * {@code legacy-jdbc} module that the driver was moved to. */
class NoDriverTest {
  @Test void thereIsNoDriver() {
    assertThrows(ClassNotFoundException.class,
        () -> Class.forName("org.apache.calcite.jdbc.Driver"));
    assertThrows(ClassNotFoundException.class,
        () -> Class.forName("org.apache.calcite.jdbc.CalciteConnectionImpl"));
    assertFalse(LegacyJdbc.available());
    assertThrows(SQLException.class,
        () -> DriverManager.getConnection("jdbc:calcite:"));
    // Core still depends on Avatica for utility classes, and Avatica
    // registers its own remote driver; but nothing answers to our URL.
    for (Driver driver : Collections.list(DriverManager.getDrivers())) {
      try {
        assertFalse(driver.acceptsURL("jdbc:calcite:"),
            driver.getClass().getName());
      } catch (SQLException e) {
        throw new AssertionError(e);
      }
    }
  }

  /** A whole session: parse, lower, validate, plan, execute, write. */
  @Test void sessionRunsWithoutOne() {
    try (Session session = Monoql.builder().build()) {
      run(session, "create table t (a int, b varchar)",
          "insert into t values (1, 'x'), (2, 'y')",
          "create view v as select b from t where a > 1",
          "grant select on v to role r");
      assertEquals(java.util.List.of("[y]"), rows(session, "select * from v"));
      assertEquals(1, rows(session, "show grants").size());
    }
    thereIsNoDriver();
  }

  /** Calcite's own Frameworks entry points, which upstream implements by
   * opening a connection to itself. */
  @Test void frameworksRunsWithoutOne() throws Exception {
    final SchemaPlus rootSchema = Frameworks.createRootSchema(true);
    // The old "metadata" schema needed the driver, and is gone with it.
    assertTrue(rootSchema.subSchemas().get("metadata") == null);
    final FrameworkConfig config =
        Frameworks.newConfigBuilder().defaultSchema(rootSchema).build();

    final RelBuilder builder = RelBuilder.create(config);
    final RelNode rel =
        builder.values(new String[] {"A", "B"}, 1, "x", 2, "y")
            .filter(
                builder.call(SqlStdOperatorTable.GREATER_THAN,
                    builder.field("A"), builder.literal(1)))
            .project(builder.field("B"))
            .build();
    assertTrue(RelOptUtil.toString(rel).contains("LogicalValues"),
        RelOptUtil.toString(rel));

    final String typeName =
        Frameworks.withPlanner((cluster, relOptSchema, schema) ->
            cluster.getTypeFactory().getClass().getSimpleName(), config);
    assertEquals("JavaTypeFactoryImpl", typeName);

    final Planner planner = Frameworks.getPlanner(config);
    final SqlNode parsed = planner.parse("select 1 + 2 as three");
    final RelNode converted = planner.rel(planner.validate(parsed)).rel;
    assertTrue(RelOptUtil.toString(converted).contains("LogicalValues")
        || RelOptUtil.toString(converted).contains("LogicalProject"));
    thereIsNoDriver();
  }
}
