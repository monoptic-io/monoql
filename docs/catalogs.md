# Catalogs

A catalog is where metadata lives: what schemas and tables exist, who may do
what, which triggers are paused. monoql does not own any of that. It asks a
`Catalog`, and a `Catalog` is something you can implement.

The API is in `io.monoptic.monoql.catalog`, in the `monoql-catalog` module.

## The idea

A catalog is a bundle of **metadata tables**. Each is a small relation of
typed rows with four operations:

```java
public interface MetaTable<R extends MetaRow> {
  String name();
  default Iterable<R> scan()                { throw unsupported }
  default Optional<R> get(Key key)          { scans }
  default void insert(R row)                { throw unsupported }
  default void update(R before, R after)    { throw unsupported }
  default void delete(R row)                { throw unsupported }
}
```

That is the entire write path. There is no `grant()` method, no
`createTable()`, no `pauseTrigger()`. Statements are rewritten to DML on
these tables ([SQL reference](sql.md)), and DML on these tables becomes these
calls:

| SQL | Call |
|---|---|
| `SELECT`, `SHOW`, `DESCRIBE` | `scan` |
| `INSERT`, `CREATE`, `GRANT`, `FIRE` | `insert` |
| `UPSERT`, `CREATE OR REPLACE`, `GRANT` of something already granted | `get`, then `update` or `insert` |
| `UPDATE`, `ALTER`, `PAUSE`, `RESUME`, `COMMENT ON` | `scan`, then `update` |
| `DELETE`, `DROP`, `REVOKE` | `scan`, then `delete` |

## Everything is optional

`Catalog` has one accessor per metadata table, and each has a default that
returns a table supporting nothing:

```java
public interface Catalog extends AutoCloseable {
  default MetaTable<SchemaInfo> schemas()                     // CREATE SCHEMA ...
  default MetaTable<TableInfo> tables()                       // CREATE TABLE, ALTER TABLE ...
  default MetaTable<ViewInfo> views()
  default MetaTable<MaterializedViewInfo> materializedViews()
  default MetaTable<PartitionInfo> partitions()               // ALTER TABLE ... ADD PARTITION
  default MetaTable<FunctionInfo> functions()
  default MetaTable<TriggerInfo> triggers()
  default MetaTable<Job> jobs()                               // FIRE TRIGGER
  default MetaTable<RoleInfo> roles()
  default MetaTable<UserInfo> users()
  default MetaTable<Grant> grants()                           // GRANT ... ON, REVOKE ... ON
  default MetaTable<RoleGrant> roleGrants()                   // GRANT ROLE, REVOKE ROLE
  default Optional<Table> bind(TableInfo table)               // the data plane
  default Optional<Table> bind(MaterializedViewInfo view, List<Column> columns)
  default void close()
}
```

So an adapter for a system that only understands ACLs is this:

```java
public class AclCatalog implements Catalog {
  @Override public MetaTable<Grant> grants() {
    return new MetaTable<>() {
      @Override public String name() { return "GRANTS"; }
      @Override public Iterable<Grant> scan()   { return client.listAcls()...; }
      @Override public void insert(Grant grant) { client.allow(...); }
      @Override public void delete(Grant grant) { client.deny(...); }
    };
  }
}
```

With it mounted, `GRANT`, `REVOKE`, `SHOW GRANTS` and DML on
`METADATA.GRANTS` work. `CREATE TABLE` fails with "This catalog does not
support INSERT on TABLES". Nothing parses and then quietly does nothing.

A catalog whose `GRANTS` table can be scanned is also *governed* by it: a
session that acts as a user is held to those grants, with roles resolved
through `ROLE_GRANTS` if the catalog has that too. A catalog with no
`GRANTS` table is not checked by monoql at all. The rules are in
[the SQL reference](sql.md#enforcement).

Support can be partial within a table, too. A read-only catalog implements
only `scan`. A table of requests (`jobs`) need only implement `insert`. A catalog may reject individual rows it cannot honor, by throwing
`UnsupportedException` from `insert`; the JDBC catalog, for instance, accepts
tables but not table options.

### What the engine does about gaps

- A **query** over a metadata table reads every mounted catalog, and skips
  the ones that do not support that table. `SELECT * FROM METADATA.GRANTS`
  works even if some catalogs have no notion of grants.
- A **statement** aimed at one catalog that finds nothing asks that catalog
  whether it supports the table at all, and reports "not supported" rather
  than "not found" (or rather than an empty answer, for `SHOW`).
- A catalog that can **remove but not list** still works for `DROP ROLE`,
  `DROP USER` and `REVOKE`: when `scan` is unsupported, the engine calls
  `delete` with the rows the statement names. `UPDATE`s have no such
  fallback, because they need to see the row they change.

## Rows

Rows are records. Their components are the columns, in order, named in upper
snake case (`schemaName` is `SCHEMA_NAME`). Each row has a `key()`: two rows
with equal keys are the same object.

| Table | Record | Key |
|---|---|---|
| `SCHEMAS` | `SchemaInfo(schemaName, comment, options)` | schema |
| `TABLES` | `TableInfo(schemaName, tableName, columns, primaryKey, partitionKeys, comment, options)` | schema, table |
| `VIEWS` | `ViewInfo(schemaName, viewName, columnNames, definition, comment)` | schema, view |
| `MATERIALIZED_VIEWS` | `MaterializedViewInfo(schemaName, viewName, columnNames, definition, schedule, paused, comment, options)` | schema, view |
| `PARTITIONS` | `PartitionInfo(schemaName, tableName, spec, options)` | schema, table, spec |
| `FUNCTIONS` | `FunctionInfo(schemaName, functionName, functionLanguage, definition, comment, options)` | schema, function |
| `TRIGGERS` | `TriggerInfo(schemaName, triggerName, tableSchema, tableName, timing, events, schedule, action, paused, comment, options)` | schema, trigger |
| `JOBS` | `Job(schemaName, jobName, submittedAt, statement, options, state, startedAt, finishedAt, error)` | schema, job, time |
| `ROLES` | `RoleInfo(roleName, comment, options)` | role |
| `USERS` | `UserInfo(userName, comment, options)` | user |
| `GRANTS` | `Grant(privilege, objectType, objectSchema, objectName, granteeType, grantee, grantable)` | all but `grantable` |
| `ROLE_GRANTS` | `RoleGrant(roleName, granteeType, grantee)` | all |

Things to know:

- **Columns travel with their table.** `TableInfo.columns` is a list of
  `Column(columnName, dataType, nullable, comment)`, so that creating a table
  is one atomic insert. `METADATA.COLUMNS` is derived from it by the engine;
  catalogs get it for free, including writes (`ALTER TABLE ... ADD COLUMN`
  arrives as an `update` of the table's row).
- **Types are strings**: `VARCHAR(20)`, `DECIMAL(10, 2)`, `INTEGER ARRAY`.
  `DataTypes` converts to and from Calcite's `RelDataType`.
- **Renames are updates** in which the key changes.
- **Grants are levelled** by which name columns are set: a catalog grant has
  neither `objectSchema` nor `objectName`; a schema grant has `objectSchema`;
  an object grant has both.
- `granteeType` is `USER`, `ROLE`, `GROUP`, or `PRINCIPAL` when the statement
  did not say. A statement that names a kind also matches rows whose kind is
  `PRINCIPAL`, so a backend that cannot tell users from roles can report
  everything as `PRINCIPAL`.

## The METADATA schema

The engine presents the tables above in a schema called `METADATA`, each
with a leading `CATALOG_NAME` column that says which catalog a row belongs
to. Reads are the union across catalogs. Writes go to the catalog the row
names, or to the current catalog if `CATALOG_NAME` is null.

Three more tables belong to the session rather than to a catalog:

| Table | Columns | |
|---|---|---|
| `CATALOGS` | `CATALOG_NAME, TYPE, COMMENT, OPTIONS` | The mounted catalogs. Inserting a row creates and mounts one. |
| `SETTINGS` | `SETTING_NAME, SETTING_VALUE` | `catalog` and `schema`: the defaults for names. Update only. |
| `COLUMNS` | `CATALOG_NAME, SCHEMA_NAME, TABLE_NAME, COLUMN_NAME, ORDINAL_POSITION, DATA_TYPE, NULLABLE, COMMENT` | Derived from `TABLES`, and from the definitions of `VIEWS` and `MATERIALIZED_VIEWS`. Only the columns of tables can be written. |

In SQL, lists are arrays, options are `MAP<VARCHAR, VARCHAR>`, timestamps
are `TIMESTAMP(3)` in UTC, and `TABLES.COLUMNS` is an array of column
declarations in `CREATE TABLE` syntax. No column name is a reserved word, so
none needs quoting. `!metadata` in the shell lists everything.

## The data plane

Metadata says a table exists; `bind` says how to read it:

```java
default Optional<org.apache.calcite.schema.Table> bind(TableInfo table)
```

- Return a Calcite `Table` (a `ScannableTable`, a `FilterableTable`, a
  `TranslatableTable` with its own rules and convention, ...) and queries can
  read it, with whatever pushdown that table supports.
- Also implement `WritableTable` on it, and the engine applies `INSERT`,
  `UPDATE` and `DELETE` by computing the affected rows and handing them over.
  Without it, writes are left to Calcite: a JDBC table, for example, gets the
  whole statement pushed down.
- A scan that may run long should stop when the statement is cancelled
  (`Session.cancel()`; Ctrl-C in the shell). The engine checks between the
  rows it hands back, but a query that is still working towards its first
  row can only be stopped where it reads: check
  `DataContext.Variable.CANCEL_FLAG` in the scan and throw
  `CancellationException`, as the in-memory catalog does. The `jdbc` catalog
  does not: a query waiting on another database stops when that database
  next returns a row.
- Return empty (the default) and the table is **metadata only**. It can be
  described, granted on, and used in views and plans; a query that needs its
  rows fails with "Catalog X does not provide the rows of table Y". This is
  the right answer for registries, and for planning pipelines that run
  elsewhere.

### Materialized views

A materialized view has rows too, if the catalog keeps them:

```java
default Optional<Table> bind(MaterializedViewInfo view, List<Column> columns)
```

`columns` is what the view's definition returns today. The catalog returns
what holds the view's rows, making it if it is not there and reshaping it if
the columns have changed.

- Return a table that can be written, in either of the ways a table from
  `bind(TableInfo)` can, and the engine fills it with ordinary DML: an
  `INSERT` of the definition when the view is created, and a `DELETE` and
  that `INSERT` at each `REFRESH`. Those are steps of one statement, so they
  run inside `atomically` (below); whether a reader can see the view empty
  in between is up to that.
- Return empty (the default) and the view reads as its definition.

Dropping the view should drop its rows: `StandardCatalog` calls
`materializedViewChanged(before, after)` for that.

## Atomic statements

A statement is often several changes: `DROP TABLE` removes a row of `TABLES`
and the grants on it; `CREATE TABLE ... AS` adds a table and then its rows;
an `INSERT` into a metadata table may add many rows. A catalog can make
them one:

```java
default <T> T atomically(Supplier<T> work) { return work.get(); }
```

The engine runs every statement that is not a query inside `atomically`,
for each catalog of the session. A catalog that can, undoes what the work
did to it if the work throws, and shows nobody the changes until it
returns. The default does neither, and says so by being the default.

| Catalog | |
|---|---|
| `metastore` | A database transaction. Writers to one tenant take turns, from each one's first change to its end, so that what a rule checked (the schema exists; the schema is empty) is still true when the change is made. Readers do not wait, and see what is committed. |
| `memory` | For the rows of tables and materialized views: nobody else sees what a statement writes until it ends, and nothing is written if it fails. For metadata: writers take turns, but nothing is undone. |
| `jdbc` | Each change is its own statement in the database. |

Things to know:

- **It is per catalog.** A statement that changes two catalogs is two
  units, and one can fail after the other has finished. In particular, a
  `metastore` catalog's data catalog is not part of its transaction. Its
  changes are made last, so a failure there undoes the metadata; but a
  table created there stays created if the metadata then cannot be
  committed.
- **A change is to a row as it was read.** An `UPDATE` or `DELETE` of a
  metadata row that someone else has changed since the statement read it
  fails, rather than overwrite what they did.
- **It is per statement.** There are no transactions that span statements.

## Providers

`CREATE CATALOG c WITH (type = 'x', ...)` needs something that makes
catalogs of type `x`:

```java
public interface CatalogProvider {
  String type();
  Catalog create(String name, Map<String, String> options);
}
```

Providers are found with `java.util.ServiceLoader`. Put a
`META-INF/services/io.monoptic.monoql.catalog.CatalogProvider` file in your
jar, put the jar on the class path (or pass it to the shell with `--jar`),
and the type exists. `Monoql.builder().provider(...)` registers one directly,
and `.catalog(name, catalog)` mounts an instance without a provider.

## The catalogs in this repository

**`memory`** (`MemoryCatalog`, in `monoql-catalog`) supports every table and
keeps rows on the heap. It is the reference for what each operation should
do, including the integrity rules a good backend enforces: objects live in
schemas that exist, tables and views share a namespace, a schema that has
objects cannot be dropped, partitions match their table's partition keys.
`MemoryMetaTable` is reusable on its own, to back one table of a catalog. It
keeps the rows of tables and of materialized views, on the heap.

Those rules are not the memory catalog's own. They are `StandardCatalog`
(in `io.monoptic.monoql.catalog.standard`), which keeps its rows in a
`Storage`: a map from key to row for each metadata table, and a way to make
several changes as one. The memory catalog is the standard catalog over the
heap; the next one is the standard catalog over a database. A third is a
`Storage` away.

**`metastore`** (`MetastoreCatalog`, in `monoql-metastore`) supports every
table too, and keeps its metadata in a SQL database, so that it outlives
the process and is shared by every process that mounts it.

```sql
CREATE CATALOG lake WITH (type = 'metastore',
  url = 'jdbc:postgresql://db/metastore', user = 'monoql', password = '...',
  tenant = 'acme',
  'data.type' = 'jdbc', 'data.url' = 'jdbc:postgresql://db/acme');
```

- **One database, many tenants.** A catalog sees the metadata of its
  `tenant` (by default, `default`) and its own name, and nothing else: two
  tenants can each have a catalog `LAKE` with a table `S.ORDERS`, and
  neither can tell. The tenant is fixed when the catalog is made; no
  statement can name another.
- **The rows of tables are in a data catalog**, of any type, given by
  `data.type` and that type's options with `data.` in front. Creating,
  altering and dropping a table (or a schema) does the same there, and
  queries read from it. The data catalog is told a table's shape: columns,
  keys. Comments and options stay in the metastore, as do views, grants and
  everything else, so a data catalog needs only `SCHEMAS`, `TABLES` and
  `bind`. Without a data catalog, tables are metadata only.
- **Statements are atomic** in the metastore: see below.
- **The rows of materialized views are in the data catalog too**, each
  view's in a table of its name, made as the view is created and dropped
  with it. The table keeps the shape it was made with: if what the view's
  query returns changes shape (a `SELECT *` over a table that gains a
  column, say), `CREATE OR REPLACE` the view. A refresh is as atomic as the
  data catalog makes a statement, which for `jdbc` is not at all: a reader
  can find the view empty while it is refreshed. Without a data catalog,
  materialized views read as their definitions.
- Other options: `driver`; and `create = false`, if the metastore's tables
  are made by someone else.

The metastore is two tables, which the catalog creates if it may:

```sql
CREATE TABLE monoql_tenants (
  tenant_id VARCHAR(255) NOT NULL PRIMARY KEY,
  version BIGINT NOT NULL);
CREATE TABLE monoql_metadata (
  tenant_id VARCHAR(255) NOT NULL REFERENCES monoql_tenants ON DELETE CASCADE,
  catalog_name VARCHAR(255) NOT NULL,
  table_name VARCHAR(64) NOT NULL,     -- SCHEMAS, TABLES, GRANTS, ...
  row_key VARCHAR(2000) NOT NULL,      -- the row's key, as a JSON array
  row_data TEXT NOT NULL,              -- the row, as JSON
  PRIMARY KEY (tenant_id, catalog_name, table_name, row_key));
```

A tenant's row appears when a catalog is first made for it; deleting the row
deletes the tenant's metadata, and a server still using it finds that out at
its next change. `version` counts the tenant's changes. It is developed
against PostgreSQL, and wants a database in which a writer waits for a row
that another has changed.

**`jdbc`** (`JdbcCatalog`, in `monoql-jdbc`) fronts any JDBC database, and is
an example of honest partial support:

| Table | Read | Write |
|---|---|---|
| `SCHEMAS` | yes | insert, delete |
| `TABLES` | yes | insert, delete; update to rename, or to add or drop one column |
| `GRANTS` | table privileges | insert, delete, on tables |
| `ROLES` | no | insert, delete |
| the rest | no | no |

Writes are translated to DDL and DCL in the database's dialect and executed
there. Table data goes through Calcite's JDBC adapter, so filters, joins and
aggregates are pushed down, and `INSERT`/`UPDATE`/`DELETE` run in the
database. Options: `url` (required), `driver`, `user`, `password`,
`catalog`.
