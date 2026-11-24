/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.monoptic.monoql.Sessions.dump;
import static io.monoptic.monoql.Sessions.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Tests that statements are spellings of DML: what each lowers to, and
 * that running the lowered DML by hand has the same effect as running the
 * statement. */
class LoweringTest {
  private Session session;

  @BeforeEach void open() {
    session = Monoql.builder().build();
    run(session, "create schema s", "use s",
        "create table t (a int not null, b varchar)");
  }

  @AfterEach void close() {
    session.close();
  }

  private String lower(String sql) {
    return String.join(";\n", session.lower(sql).sql());
  }

  @Test void grant() {
    assertEquals("UPSERT INTO \"METADATA\".\"GRANTS\" (\"CATALOG_NAME\", "
            + "\"PRIVILEGE\", \"OBJECT_TYPE\", \"OBJECT_SCHEMA\", "
            + "\"OBJECT_NAME\", \"GRANTEE_TYPE\", \"GRANTEE\", "
            + "\"GRANTABLE\")\n"
            + "VALUES ('MEMORY', 'SELECT', 'TABLE', 'S', 'T', 'ROLE', 'R', "
            + "FALSE),\n"
            + "  ('MEMORY', 'SELECT', 'TABLE', 'S', 'T', 'USER', 'U', FALSE)",
        lower("grant select on t to role r, user u"));
  }

  @Test void revoke() {
    assertEquals("DELETE FROM \"METADATA\".\"GRANTS\"\n"
            + "WHERE \"CATALOG_NAME\" = 'MEMORY'\n"
            + "  AND \"OBJECT_TYPE\" = 'SCHEMA'\n"
            + "  AND \"OBJECT_SCHEMA\" = 'S'\n"
            + "  AND \"OBJECT_NAME\" IS NULL\n"
            + "  AND \"PRIVILEGE\" IN ('USAGE', 'CREATE TABLE')\n"
            + "  AND \"GRANTEE_TYPE\" IN ('ROLE', 'PRINCIPAL') "
            + "AND \"GRANTEE\" = 'R'",
        lower("revoke usage, create table on schema s from role r"));
  }

  @Test void control() {
    assertEquals("UPDATE \"METADATA\".\"TRIGGERS\"\n"
            + "SET \"PAUSED\" = TRUE\n"
            + "WHERE \"CATALOG_NAME\" = 'MEMORY' AND \"SCHEMA_NAME\" = 'S' "
            + "AND \"TRIGGER_NAME\" = 'TR'",
        lower("pause trigger tr"));
    // Firing a trigger hands its action to whatever runs jobs.
    session.execute("create trigger tr schedule '@daily' as"
        + " refresh materialized view mv");
    assertEquals("INSERT INTO \"METADATA\".\"JOBS\" (\"CATALOG_NAME\", "
            + "\"SCHEMA_NAME\", \"JOB_NAME\", \"SUBMITTED_AT\", "
            + "\"SUBMITTED_BY\", "
            + "\"STATEMENT\", \"CONTINUOUS\", \"OPTIONS\")\n"
            + "VALUES ('MEMORY', 'S', 'TR', CURRENT_TIMESTAMP, CURRENT_USER, "
            + "'REFRESH MATERIALIZED VIEW \"MV\"', FALSE, MAP['why', 'test'])",
        lower("fire trigger tr with (why = 'test')"));
  }

  @Test void createTable() {
    assertEquals("INSERT INTO \"METADATA\".\"TABLES\" (\"CATALOG_NAME\", "
            + "\"SCHEMA_NAME\", \"TABLE_NAME\", \"COLUMNS\", "
            + "\"PRIMARY_KEY\", \"PARTITION_KEYS\", \"COMMENT\", "
            + "\"OPTIONS\")\n"
            + "VALUES ('MEMORY', 'S', 'U', ARRAY['\"ID\" INTEGER NOT NULL', "
            + "'\"NAME\" VARCHAR(10) COMMENT ''n'''], ARRAY['ID'], NULL, "
            + "NULL, MAP['k', 'v'])",
        lower("create table u (id int not null primary key, "
            + "name varchar(10) comment 'n') with (k = 'v')"));
  }

  @Test void dropAndUse() {
    assertEquals("DELETE FROM \"METADATA\".\"TABLES\"\n"
            + "WHERE \"CATALOG_NAME\" = 'MEMORY' AND \"SCHEMA_NAME\" = 'S' "
            + "AND \"TABLE_NAME\" = 'T'",
        lower("drop table t"));
    assertEquals("UPDATE \"METADATA\".\"SETTINGS\"\n"
            + "SET \"SETTING_VALUE\" = 'MEMORY'\n"
            + "WHERE \"SETTING_NAME\" = 'catalog';\n"
            + "UPDATE \"METADATA\".\"SETTINGS\"\n"
            + "SET \"SETTING_VALUE\" = 'MAIN'\n"
            + "WHERE \"SETTING_NAME\" = 'schema'",
        lower("use memory.main"));
  }

  @Test void queriesAndDmlAreAlreadyLow() {
    assertEquals("select * from t", lower("select * from t"));
    assertEquals("insert into t values (1, 'a')",
        lower("insert into t values (1, 'a')"));
  }

  /** The core promise. For each statement, a second session runs the
   * lowered DML instead; afterwards the two catalogs must be identical. */
  @Test void statementsAndTheirDmlHaveTheSameEffect() {
    final List<String> statements =
        List.of("create schema x comment 'c' with (k = 'v')",
            "create table x.u (id int not null primary key, n varchar(5) "
                + "comment 'n') partitioned by (n) with (a = 'b')",
            "alter table x.u add column extra decimal(10, 2)",
            "alter table x.u add partition (n = 'one') with (loc = '/1')",
            "alter table x.u add partition (n = 'two')",
            "alter table x.u drop partition (n = 'two')",
            "alter table x.u set (c = 'd')",
            "comment on table x.u is 'the table'",
            "comment on column x.u.extra is 'the extra'",
            "create view x.v (p, q) comment 'vc' as select a, b from t",
            "create materialized view x.mv schedule '@daily' with (e = 'f') "
                + "as select count(*) as c from t",
            "pause materialized view x.mv",
            "create materialized view x.live refresh continuously "
                + "as select a from t",
            "create materialized view x.gone refresh continuously "
                + "as select b from t",
            "drop materialized view x.gone",
            "create function x.f language python comment 'fc' as 'pass'",
            "create trigger x.tr after insert or delete on x.u "
                + "schedule '@hourly' with (g = 'h') as 'go'",
            "pause trigger x.tr",
            "resume trigger x.tr",
            "create role r comment 'rc'",
            "create role r2",
            "create user u with (email = 'u@example.com')",
            "grant select, insert on x.u to role r, user u "
                + "with grant option",
            "grant usage on schema x to r",
            "grant all on catalog memory to group g",
            "revoke grant option for insert on x.u from user u",
            "revoke select on x.u from role r",
            "grant role r, r2 to user u",
            "revoke role r2 from user u",
            "alter table x.u rename to w",
            "alter table x.w drop column extra",
            "drop role r2",
            "create function x.f2 as 'com.example.F2'",
            "drop function x.f2",
            "create or replace view x.v as select a from t",
            "create table if not exists x.w (z int)",
            "drop table if exists x.nosuch");
    try (Session other = Monoql.builder().build()) {
      run(other, "create schema s", "use s",
          "create table t (a int not null, b varchar)");
      for (String statement : statements) {
        // Lower before executing, as lowering may read current state.
        final var lowered = other.lower(statement);
        session.execute(statement);
        for (var step : lowered.steps()) {
          try {
            other.execute(step.sql());
          } catch (io.monoptic.monoql.catalog.AlreadyExistsException e) {
            // The one thing the bare INSERT does differently from
            // CREATE ... IF NOT EXISTS: it reports the collision.
            assertEquals(true, step.ignoreConflict(), statement);
          }
        }
      }
      for (String table
          : List.of("SCHEMAS", "TABLES", "COLUMNS", "PARTITIONS", "VIEWS",
              "MATERIALIZED_VIEWS", "FUNCTIONS", "TRIGGERS", "ROLES", "USERS",
              "GRANTS", "ROLE_GRANTS")) {
        final List<String> expected = dump(session, table);
        assertEquals(expected, dump(other, table), table);
        assertFalse(expected.isEmpty(), table + " was not exercised");
      }
    }
  }
}
