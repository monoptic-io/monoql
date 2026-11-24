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
| `CREATE MATERIALIZED VIEW` | `INSERT INTO METADATA.MATERIALIZED_VIEWS`, then `INSERT INTO` the view, of its query |
| `DROP MATERIALIZED VIEW` | `DELETE FROM METADATA.MATERIALIZED_VIEWS` |
| `PAUSE`, `RESUME` | `UPDATE METADATA.MATERIALIZED_VIEWS SET PAUSED` |
| `REFRESH` | `DELETE FROM` the view, then `INSERT INTO` it, of its query |

The query is validated when the view is created, and stored with every name
fully qualified, so a view means the same thing whatever the current schema
is when it is read.

A materialized view has rows of its own if its catalog keeps them, as the
in-memory catalog does. They are what the query returned when the view was
created, or last refreshed; in between, they are as stale as that.

```sql
CREATE MATERIALIZED VIEW totals AS SELECT region, SUM(amount) AS total
  FROM pg.public.orders GROUP BY region;   -- runs the query, keeps the rows
SELECT * FROM totals;                      -- reads the kept rows
REFRESH MATERIALIZED VIEW totals;          -- runs the query again
```

- The query may read any catalog, so a materialized view is also a way to
  copy rows from one catalog to another.
- `REFRESH` is a `DELETE` of the rows and an `INSERT` of the query's, as
  `!lower` shows; there is no incremental refresh. They are one statement,
  so in a catalog that makes statements atomic, as the in-memory catalog
  does for rows, a reader sees the old rows or the new, and if the query
  fails the old rows stay. Run by hand, they are two statements.
- The rows of a materialized view are its owner's to write, with ordinary
  DML; that is all a refresh is. Refreshing therefore takes ownership of the
  view, and `SELECT` on what its query reads. Reading the view takes
  `SELECT` on the view alone.
- `CREATE ... IF NOT EXISTS` leaves a view that exists as it is, rows and
  all; `CREATE OR REPLACE` empties it and fills it again.
- `SCHEDULE` and `PAUSE` are recorded for whatever runs schedules. monoql
  does not: it is a library, and has no process to run them in.
- In a catalog that does not keep the rows, a materialized view reads as its
  query, like a view. The step that fills it when it is created is
  skipped, and `REFRESH` is an error: there is nothing to refresh.
- A query is never rewritten to read a materialized view it does not name.

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
| `FIRE` | `INSERT INTO METADATA.JOBS`, of the trigger's action |

A trigger is deliberately general. With a timing and events it is a classic
table trigger; with only a schedule it is a scheduled task; with only a table
it is a change hook. The action is a string, which means whatever the catalog
takes it to mean; or a SQL statement, which is checked by the parser and
stored as text.

### Jobs

A job is a statement for something else to run: another engine, a worker,
whatever stands behind the catalog. There is no syntax for one. It is a row
of `METADATA.JOBS`:

```sql
INSERT INTO metadata.jobs (schema_name, job_name, submitted_at, statement)
VALUES ('S', 'nightly', CURRENT_TIMESTAMP, 'REFRESH MATERIALIZED VIEW s.totals');
SELECT job_name, state, finished_at, error FROM metadata.jobs;
DELETE FROM metadata.jobs WHERE job_name = 'nightly';      -- cancels it
```

- Inserting the row submits the job, and deleting it cancels it. Several
  jobs may have the same name; when they were submitted tells them apart.
- `FIRE TRIGGER` submits the trigger's action as a job of the trigger's
  name, with the options of `FIRE` as the job's.
- A trigger's action can itself be an `INSERT INTO metadata.jobs`, which
  makes the trigger a schedule and a job to submit on it.
- `STATE`, `STARTED_AT`, `FINISHED_AT` and `ERROR` say what happened. They
  are written by whatever runs the job, and a statement that sets them is
  rejected.
- monoql runs no jobs: it is a library, and has no process to run them in.
  In the in-memory catalog a job is a row that nothing acts on.
- In a catalog with grants, writing `JOBS` takes ownership of the schema.

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
  (`'roles/bigquery.dataViewer'`). `ALL` is `ALL PRIVILEGES`. Any privilege
  can be recorded; the ones monoql itself acts on are listed under
  [Enforcement](#enforcement).
- A grantee's name is an identifier or a string (`'bob@example.com'`). If the
  kind is not given, it is recorded as `PRINCIPAL`.
- Granting what is already granted is not an error, and can change
  grantability. Revoking what was never granted is not an error.
- `REVOKE ALL ON x FROM y` removes every privilege `y` holds on `x`.
- A `GRANT` with no `ON` clause grants roles, with or without the word `ROLE`.

### Enforcement

A session may act as someone
(`Monoql.builder().identity(Identity.of("ANN", "ANALYSTS"))`: a user, and the
groups the user is in), and then every statement is checked against the
grants. A session with no identity is not checked; that is how grants are
set up in the first place. `session.as(identity)` gives another session over
the same catalogs, which is how an application serves many users.

monoql does not authenticate anyone, and does not keep group membership: the
application that builds the session has established both, and vouches for
them. Nor can it enforce anything against the application itself, which holds
the catalogs. The identity is shown by `SHOW SETTINGS` (`user`, `groups`) and
`CURRENT_USER`, and cannot be changed.

| To | The user needs |
|---|---|
| Read a table, view or materialized view | `SELECT` on it |
| `INSERT`, `UPDATE`, `DELETE` rows of a table | that privilege on it |
| Create a table, view, materialized view, function or trigger | `CREATE TABLE`, `CREATE VIEW`, `CREATE MATERIALIZED VIEW`, `CREATE FUNCTION` or `CREATE TRIGGER` on the schema |
| Create a schema | `CREATE SCHEMA` on the catalog |
| Alter, comment on, pause, resume, replace or drop something | `OWNERSHIP` of it |
| Fire a trigger, or submit or cancel a job any other way | `OWNERSHIP` of the schema |
| Write the rows of a materialized view, which refreshing does | `OWNERSHIP` of it, and `SELECT` on what the rows are read from |
| `GRANT` or `REVOKE` a privilege on something | `OWNERSHIP` of it; or that privilege on it `WITH GRANT OPTION` |
| Anything about roles and users | `OWNERSHIP` of the catalog |
| `CREATE CATALOG`, `DROP CATALOG` | a session with no user |
| Read the `METADATA` schema (`SHOW`, `DESCRIBE`); `USE`; `SET` | nothing |

- **Whoever creates something owns it.** Creating an object as a user adds
  a row to `METADATA.GRANTS`: `OWNERSHIP` of it, to that user, grantable.
  Ownership can be granted to others, and revoked, like any privilege.
  What a session with no user creates has no owner, until one is granted.
- `OWNERSHIP` of something includes every privilege on it. `ALL PRIVILEGES`
  on a schema or a catalog amounts to owning it; on a single object it is
  every privilege *except* ownership, so it does not allow dropping it.
- A grant on a schema or a catalog covers everything in it. So the owner
  of a schema can do anything to anything in it, and a user with
  `CREATE TABLE` on a catalog can create tables in any of its schemas.
- **Grants belong to an object, not to its name.** Dropping something
  deletes the grants on it, and renaming it moves them, whoever does it.
  Moving a table to another schema takes `CREATE TABLE` there.
- A grant applies to a user if its grantee is that user, a group the user
  is in, or a role that any of those holds, directly or through other roles
  (`GRANT ROLE`). A grantee recorded without a kind matches any of them.
  Names are compared exactly, and an unquoted name in a statement is stored
  in upper case.
- A view is read with its definer's rights: `SELECT` on the view is enough,
  whatever it reads. To define one, you need `SELECT` on what it reads.
- Because a statement *is* its DML, `DROP TABLE t` and
  `DELETE FROM METADATA.TABLES WHERE ...` are checked by the same rule, on
  the rows they change; and `INSERT INTO METADATA.TABLES` makes you the
  owner just as `CREATE TABLE` does.
- A catalog is checked only if it has a `GRANTS` table that can be read. One
  that does not (the `jdbc` catalog, for example) is left to its backend.

Not there yet: separate privileges for the different ways of changing an
object (they all take ownership); `EXECUTE` on functions; and
restricting who can read metadata, which includes view definitions and the
grants themselves.

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
