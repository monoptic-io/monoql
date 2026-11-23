# SQL reference

monoql's SQL is Calcite's SQL: queries, `INSERT`, `UPDATE`, `DELETE`,
`MERGE`, `UPSERT`, and `EXPLAIN PLAN FOR` are unchanged, and documented in the
[Calcite reference](https://calcite.apache.org/docs/reference.html). This page
covers what monoql adds.

Every statement here is shorthand for DML, or a query, against the
[`METADATA` schema](catalogs.md#the-metadata-schema). The right-hand column of
each table says which. To see the exact SQL for a particular statement, use
`!lower` in the shell or `Session.lower(sql)`.

**The rule of the grammar** is that nothing is accepted that is not carried
out. If a clause cannot be represented in a metadata row, the parser rejects
it. If a catalog cannot honor a row, the catalog rejects it.

## Names

Names have up to three parts: `catalog.schema.object`. Missing parts come
from the session's current catalog and schema (see [`USE`](#use-and-set)).
Roles and users belong to a catalog, not a schema: `catalog.role`.

Identifiers follow the SQL standard. Unquoted, they fold to upper case
(`orders` is `ORDERS`); in double quotes, they are taken as written.

## Common clauses

```
COMMENT 'text'
WITH (key = value, ...)
```

- `OPTIONS (...)` and `TBLPROPERTIES (...)` are accepted in place of `WITH`.
- The `=` is optional, here and after `COMMENT` and `SCHEDULE`.
- A key is a string (`'connector'`), a word (`type`), or dotted words
  (`retention.ms`). Keys are case-sensitive and are **not** identifiers:
  `user`, `table` and other SQL keywords are fine, and nothing is folded.
- A value is a string, a number, `TRUE` or `FALSE`. All are stored as
  strings.

`CREATE` statements take the usual modifiers:

| Form | Meaning | DML |
|---|---|---|
| `CREATE x` | Fails if `x` exists. | `INSERT` |
| `CREATE x IF NOT EXISTS` | Does nothing if `x` exists. | `INSERT`, ignoring a collision |
| `CREATE OR REPLACE x` | Replaces `x` if it exists. | `UPSERT` |

`DROP x` fails if `x` does not exist; `DROP x IF EXISTS` does not.

## Catalogs and schemas

```
CREATE [OR REPLACE] CATALOG [IF NOT EXISTS] name
  [COMMENT 'text'] WITH (type = '...', ...)
DROP CATALOG [IF EXISTS] name

CREATE [OR REPLACE] {SCHEMA | DATABASE} [IF NOT EXISTS] [catalog.]name
  [COMMENT 'text'] [WITH (...)]
DROP {SCHEMA | DATABASE} [IF EXISTS] [catalog.]name
```

| Statement | DML |
|---|---|
| `CREATE CATALOG` | `INSERT INTO METADATA.CATALOGS` |
| `DROP CATALOG` | `DELETE FROM METADATA.CATALOGS` |
| `CREATE SCHEMA` | `INSERT INTO METADATA.SCHEMAS` |
| `DROP SCHEMA` | `DELETE FROM METADATA.SCHEMAS` |

`type` selects a [catalog provider](catalogs.md#providers); the remaining
options are passed to it. `memory` is built in. `jdbc` comes with the
`monoql-jdbc` module:

```sql
CREATE CATALOG pg WITH (type = 'jdbc', url = 'jdbc:postgresql://host/db',
  user = 'me', password = 'secret');
```

## Tables

```
CREATE [OR REPLACE] TABLE [IF NOT EXISTS] name
  [ ( column [, ...] [, PRIMARY KEY (name [, ...])] ) ]
  [PARTITIONED BY (name [, ...])]
  [COMMENT 'text'] [WITH (...)]
  [AS query]

column: name type [NOT NULL | NULL] [PRIMARY KEY] [COMMENT 'text']

DROP TABLE [IF EXISTS] name

ALTER TABLE name ADD COLUMN column
ALTER TABLE name DROP COLUMN name
ALTER TABLE name RENAME TO name
ALTER TABLE name SET (key = value, ...)
ALTER TABLE name ADD [IF NOT EXISTS] PARTITION (column = value, ...) [WITH (...)]
ALTER TABLE name DROP [IF EXISTS] PARTITION (column = value, ...)
```

A table needs a column list or `AS query`, not both.

| Statement | DML |
|---|---|
| `CREATE TABLE` | `INSERT INTO METADATA.TABLES` |
| `CREATE TABLE ... AS query` | the same, then `INSERT INTO` the new table |
| `DROP TABLE` | `DELETE FROM METADATA.TABLES` |
| `ADD COLUMN` | `INSERT INTO METADATA.COLUMNS` |
| `DROP COLUMN` | `DELETE FROM METADATA.COLUMNS` |
| `RENAME TO` | `UPDATE METADATA.TABLES SET SCHEMA_NAME, TABLE_NAME` |
| `SET (...)` | `UPDATE METADATA.TABLES SET OPTIONS` (merged with the existing options) |
| `ADD PARTITION` | `INSERT INTO METADATA.PARTITIONS` |
| `DROP PARTITION` | `DELETE FROM METADATA.PARTITIONS` |

In `METADATA.TABLES`, the columns of a table are an array of column
declarations, in the syntax above:

```sql
INSERT INTO metadata.tables (schema_name, table_name, columns)
VALUES ('MAIN', 'T', ARRAY['id INT NOT NULL', 'name VARCHAR(20) COMMENT ''who''']);
```

## Views and materialized views

```
CREATE [OR REPLACE] VIEW [IF NOT EXISTS] name [(column [, ...])]
  [COMMENT 'text'] AS query
DROP VIEW [IF EXISTS] name

CREATE [OR REPLACE] MATERIALIZED VIEW [IF NOT EXISTS] name [(column [, ...])]
  [SCHEDULE 'cron'] [COMMENT 'text'] [WITH (...)] AS query
DROP MATERIALIZED VIEW [IF EXISTS] name
PAUSE MATERIALIZED VIEW name
RESUME MATERIALIZED VIEW name
REFRESH MATERIALIZED VIEW name
```

| Statement | DML |
|---|---|
| `CREATE VIEW` | `INSERT INTO METADATA.VIEWS` |
| `DROP VIEW` | `DELETE FROM METADATA.VIEWS` |
| `CREATE MATERIALIZED VIEW` | `INSERT INTO METADATA.MATERIALIZED_VIEWS` |
| `DROP MATERIALIZED VIEW` | `DELETE FROM METADATA.MATERIALIZED_VIEWS` |
| `PAUSE`, `RESUME` | `UPDATE METADATA.MATERIALIZED_VIEWS SET PAUSED` |
| `REFRESH` | `INSERT INTO METADATA.REFRESHES` |

The query is validated when the view is created, and stored with every name
fully qualified, so a view means the same thing whatever the current schema
is when it is read.

## Functions

```
CREATE [OR REPLACE] FUNCTION [IF NOT EXISTS] name
  [LANGUAGE language] [COMMENT 'text'] [WITH (...)] AS 'definition'
DROP FUNCTION [IF EXISTS] name
```

| Statement | DML |
|---|---|
| `CREATE FUNCTION` | `INSERT INTO METADATA.FUNCTIONS` |
| `DROP FUNCTION` | `DELETE FROM METADATA.FUNCTIONS` |

The language defaults to `JAVA`, for which the definition is a class name
(a scalar function with an `eval` method, a table function, or an aggregate),
or `Class#method`. The engine can call `JAVA` functions. Functions in other
languages are recorded for the backend to run; the engine does not call them.

## Triggers

```
CREATE [OR REPLACE] TRIGGER [IF NOT EXISTS] name
  [{BEFORE | AFTER} event [OR event ...]] [ON table]
  [SCHEDULE 'cron'] [COMMENT 'text'] [WITH (...)]
  {AS 'action' | AS statement | EXECUTE statement}

event: INSERT | UPDATE | DELETE

DROP TRIGGER [IF EXISTS] name
PAUSE TRIGGER name
RESUME TRIGGER name
FIRE TRIGGER name [WITH (...)]
```

| Statement | DML |
|---|---|
| `CREATE TRIGGER` | `INSERT INTO METADATA.TRIGGERS` |
| `DROP TRIGGER` | `DELETE FROM METADATA.TRIGGERS` |
| `PAUSE`, `RESUME` | `UPDATE METADATA.TRIGGERS SET PAUSED` |
| `FIRE` | `INSERT INTO METADATA.FIRINGS` |

A trigger is deliberately general. With a timing and events it is a classic
table trigger; with only a schedule it is a scheduled task; with only a table
it is a change hook. The action is a string, which means whatever the catalog
takes it to mean; or a SQL statement, which is checked by the parser and
stored as text. The options of `FIRE` are the parameters of that firing.

## Roles, users and privileges

```
CREATE [OR REPLACE] {ROLE | USER} [IF NOT EXISTS] [catalog.]name
  [COMMENT 'text'] [WITH (...)]
DROP {ROLE | USER} [IF EXISTS] [catalog.]name

GRANT privilege [, ...] ON [kind] object TO grantee [, ...] [WITH GRANT OPTION]
REVOKE [GRANT OPTION FOR] privilege [, ...] ON [kind] object FROM grantee [, ...]

GRANT [ROLE] role [, ...] TO grantee [, ...]
REVOKE [ROLE] role [, ...] FROM grantee [, ...]

kind:    CATALOG | SCHEMA | DATABASE | TABLE | VIEW | MATERIALIZED VIEW
       | FUNCTION | TRIGGER            -- default TABLE
grantee: [USER | ROLE | GROUP] name
```

| Statement | DML |
|---|---|
| `CREATE ROLE`, `CREATE USER` | `INSERT INTO METADATA.ROLES`, `USERS` |
| `DROP ROLE`, `DROP USER` | `DELETE FROM METADATA.ROLES`, `USERS` |
| `GRANT ... ON` | `UPSERT INTO METADATA.GRANTS`, one row per privilege and grantee |
| `REVOKE ... ON` | `DELETE FROM METADATA.GRANTS` |
| `REVOKE GRANT OPTION FOR` | `UPDATE METADATA.GRANTS SET GRANTABLE = FALSE` |
| `GRANT ROLE` | `UPSERT INTO METADATA.ROLE_GRANTS` |
| `REVOKE ROLE` | `DELETE FROM METADATA.ROLE_GRANTS` |

- A privilege is one or more words (`SELECT`, `CREATE TABLE`, `USE SCHEMA`),
  stored in upper case; or a string or quoted identifier, stored as written
  (`'roles/bigquery.dataViewer'`). `ALL` is `ALL PRIVILEGES`. monoql does not
  have a fixed list of privileges; the catalog decides what it recognizes.
- A grantee's name is an identifier or a string (`'bob@example.com'`). If the
  kind is not given, it is recorded as `PRINCIPAL`.
- Granting what is already granted is not an error, and can change
  grantability. Revoking what was never granted is not an error.
- `REVOKE ALL ON x FROM y` removes every privilege `y` holds on `x`.
- A `GRANT` with no `ON` clause grants roles, with or without the word `ROLE`.

## SHOW and DESCRIBE

```
SHOW CATALOGS [LIKE 'pattern']
SHOW {SCHEMAS | DATABASES} [{IN | FROM} catalog] [LIKE 'pattern']
SHOW {TABLES | VIEWS | MATERIALIZED VIEWS | FUNCTIONS | TRIGGERS}
  [{IN | FROM} schema] [LIKE 'pattern']
SHOW {ROLES | USERS} [{IN | FROM} catalog] [LIKE 'pattern']
SHOW COLUMNS {IN | FROM} table [LIKE 'pattern']
SHOW PARTITIONS {IN | FROM} table
SHOW GRANTS [ON [kind] object] [TO grantee]
SHOW SETTINGS

DESCRIBE [TABLE] {table | view} [column]
DESCRIBE {SCHEMA | DATABASE} schema
```

`DESCRIBE` and `SHOW COLUMNS` work on views and materialized views as well as
tables. A view's columns are derived from its definition each time, so a view
whose definition is no longer valid (because a table it reads was dropped,
say) has none, and `DESCRIBE` of it fails. Write `DESCRIBE TABLE x` if the
name is a keyword such as `m` or `k`; without `TABLE`, Calcite's grammar
takes what follows for a statement to explain.

Each is a `SELECT` from the metadata table of the same name (`DESCRIBE` reads
`METADATA.COLUMNS` and `METADATA.SCHEMAS`). For anything they do not cover,
query the `METADATA` schema directly; it joins, filters and aggregates like
any other data.

## USE and SET

```
USE [CATALOG] catalog
USE [SCHEMA | DATABASE] [catalog.]schema
SET name = value
RESET name
```

The current catalog and schema are the two rows of `METADATA.SETTINGS`, named
`catalog` and `schema`; these statements update them. Setting the catalog
clears the schema. A bare `USE x` means the schema `x` of the current catalog
if there is one, and otherwise the catalog `x`.

## COMMENT ON

```
COMMENT ON kind name IS {'text' | NULL}

kind: CATALOG | SCHEMA | DATABASE | TABLE | VIEW | MATERIALIZED VIEW
    | FUNCTION | TRIGGER | ROLE | USER | COLUMN
```

An `UPDATE` of the `COMMENT` column of the object's row. For a column, the
name is `table.column`.
