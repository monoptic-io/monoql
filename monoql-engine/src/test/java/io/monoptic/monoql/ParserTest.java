/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql;

import io.monoptic.monoql.sql.SqlGrant;

import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.dialect.CalciteSqlDialect;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests the grammar extensions. */
class ParserTest {
  private static SqlNode parse(String sql) throws SqlParseException {
    return SqlParser.create(sql, Monoql.parserConfig()).parseStmt();
  }

  private static String unparse(SqlNode node) {
    return node.toSqlString(CalciteSqlDialect.DEFAULT).getSql();
  }

  /** Every statement must survive a round trip: what the parser builds, it
   * can write; and what it writes, it reads back the same. That is the
   * guard against clauses that are accepted and then dropped. */
  @ParameterizedTest
  @ValueSource(strings = {
      "create catalog c with (type = 'memory')",
      "create or replace catalog c comment 'x' with (type = 'jdbc', url = 'jdbc:x', \"pool.size\" = 4)",
      "create catalog c with (type = 'jdbc', user = 'sa', password = '', table = 't', select.from.where = 1)",
      "create schema s",
      "create database if not exists c.s comment 'c' with (owner = 'me')",
      "create table t (a int)",
      "create table if not exists c.s.t (a int not null, b varchar(10) comment 'bee', c decimal(10, 2), d int array, primary key (a, b)) partitioned by (b) comment 'tbl' with ('connector' = 'kafka', retention.ms = 1000)",
      "create or replace table t (a int primary key)",
      "create table t tblproperties ('k' = 'v') as select 1 as a",
      "create view v as select 1 as a",
      "create or replace view s.v (x, y) comment 'c' as select a, b from t",
      "create materialized view mv as select 1 as a",
      "create materialized view mv refresh continuously comment 'c' as select a from t",
      "create materialized view if not exists mv (x) schedule '@daily' comment 'c' options (k = 'v') as select a from t",
      "create function f as 'com.example.F'",
      "create or replace function s.f language python comment 'c' with (k = 'v') as 'def f(): pass'",
      "create function f as 'x' language sql",
      "create trigger tr as 'do it'",
      "create trigger tr schedule '@hourly' as refresh materialized view mv",
      "create or replace trigger if not exists s.tr after insert or update on s.t comment 'c' with (k = 'v') execute insert into u select * from t",
      "create trigger tr before delete on t as 'veto'",
      "create role r",
      "create role if not exists c.r comment 'analysts' with (k = 'v')",
      "create user u comment 'bob' with (email = 'bob@example.com')",
      "drop catalog c",
      "drop schema if exists c.s",
      "drop database s",
      "drop table if exists c.s.t",
      "drop view v",
      "drop materialized view if exists mv",
      "drop function f",
      "drop trigger tr",
      "drop role r",
      "drop user if exists u",
      "grant select on t to r",
      "grant select, insert, delete on table s.t to role r, user u, group g with grant option",
      "grant all privileges on schema s to role r",
      "grant all on catalog c to u",
      "grant create table, use schema on database s to 'alice@example.com'",
      "grant \"roles/bigquery.dataViewer\" on table t to user 'serviceAccount:x@y.com'",
      "grant execute on function f to role r",
      "grant select on materialized view mv to r",
      "grant role r to user u",
      "grant r1, r2 to u, role r3",
      "revoke select on t from r",
      "revoke all on schema s from role r",
      "revoke grant option for select, insert on table t from user u",
      "revoke role r from user u",
      "revoke r from u",
      "pause trigger tr",
      "resume trigger s.tr",
      "pause materialized view mv",
      "resume materialized view c.s.mv",
      "fire trigger tr",
      "fire trigger tr with (reason = 'backfill', \"from\" = '2026-01-01')",
      "refresh materialized view mv",
      "alter table t add column c int not null comment 'c'",
      "alter table t drop column c",
      "alter table t rename to u",
      "alter table s.t rename to s2.u",
      "alter table t set (k = 'v')",
      "alter table t set tblproperties ('k' = 'v', 'k2' = 'v2')",
      "alter table t add partition (ds = '2026-01-01', region = 'us') with (location = 's3://b')",
      "alter table t add if not exists partition (ds = '2026-01-01')",
      "alter table t drop partition (ds = '2026-01-01')",
      "alter table t drop if exists partition (n = 1)",
      "comment on table t is 'c'",
      "comment on column s.t.c is 'c'",
      "comment on schema s is null",
      "comment on materialized view mv is 'c'",
      "show catalogs",
      "show catalogs like 'M%'",
      "show schemas",
      "show databases in c like 'S%'",
      "show tables",
      "show tables from c.s like 'T%'",
      "show views in s",
      "show materialized views",
      "show columns in t",
      "show partitions from s.t",
      "show functions",
      "show triggers in s",
      "show roles",
      "show users in c",
      "show grants",
      "show grants on table s.t",
      "show grants on schema s to role r",
      "show grants to u",
      "show settings",
      "use c",
      "use c.s",
      "use catalog c",
      "use schema s",
      "use database c.s"})
  void roundTrip(String sql) throws SqlParseException {
    final String once = unparse(parse(sql));
    final String twice = unparse(parse(once));
    assertEquals(once, twice, sql);
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "create table t",
      "create table t (a int) as select 1",
      "create view v schedule '@daily' as select 1",
      "create view v with (k = 'v') as select 1",
      "create trigger tr after insert as 'x'",
      "create catalog a.b with (type = 'memory')",
      "create schema s with (k = 'a', k = 'b')",
      "grant select on t from r",
      "revoke select on t to r",
      "grant role r on t to u",
      "grant r to u with grant option",
      "revoke select on t from r with grant option",
      "alter table t add column c int primary key",
      "alter table t add partition (a = 1, a = 2)",
      "show grants like 'x'",
      "show columns",
      "pause table t",
      "fire materialized view mv",
      "create view v refresh continuously as select 1 as a",
      "create materialized view mv schedule '@daily' refresh continuously as select 1 as a",
      "create materialized view mv refresh as select 1 as a",
      "refresh trigger tr",
      "drop column c"})
  void rejected(String sql) {
    assertThrows(SqlParseException.class, () -> parse(sql), sql);
  }

  @Test void privilegesAndPrincipals() throws SqlParseException {
    final SqlGrant grant =
        (SqlGrant) parse("grant select, create table, all, 'x/y' on s.t "
            + "to r, user \"Bob\", group 'eng@example.com', role admins");
    assertEquals(List.of("SELECT", "CREATE TABLE", "ALL PRIVILEGES", "x/y"),
        grant.items);
    assertEquals(
        List.of(new SqlGrant.Grantee("PRINCIPAL", "R"),
            new SqlGrant.Grantee("USER", "Bob"),
            new SqlGrant.Grantee("GROUP", "eng@example.com"),
            new SqlGrant.Grantee("ROLE", "ADMINS")),
        grant.grantees);
    assertTrue(!grant.isRoleGrant());
    assertTrue(((SqlGrant) parse("grant a to b")).isRoleGrant());
  }

  /** The new keywords are not reserved: they still work as names. */
  @Test void keywordsRemainIdentifiers() throws SqlParseException {
    parse("select comment, schedule, tables, use, pause, fire, refresh, "
        + "roles, grants, settings from views");
    // Objects can be called by the names of kinds of object.
    parse("grant select on schema to role");
    parse("use schema");
    parse("use catalog");
  }

  /** Standard SQL is untouched. */
  @Test void standardSql() throws SqlParseException {
    parse("with t as (select 1 as a) select a, count(*) over (partition by a)"
        + " from t where a in (select 1) order by 1 fetch first 3 rows only");
    parse("merge into t using u on t.a = u.a when matched then update set"
        + " b = u.b when not matched then insert (a, b) values (u.a, u.b)");
    parse("upsert into t (a) values (1)");
    parse("explain plan for select 1");
  }
}
