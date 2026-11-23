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
| `INSERT`, `CREATE`, `GRANT`, `FIRE`, `REFRESH` | `insert` |
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
  default MetaTable<Refresh> refreshes()                      // REFRESH MATERIALIZED VIEW
  default MetaTable<PartitionInfo> partitions()               // ALTER TABLE ... ADD PARTITION
  default MetaTable<FunctionInfo> functions()
  default MetaTable<TriggerInfo> triggers()
  default MetaTable<Firing> firings()                         // FIRE TRIGGER
  default MetaTable<RoleInfo> roles()
  default MetaTable<UserInfo> users()
  default MetaTable<Grant> grants()                           // GRANT ... ON, REVOKE ... ON
  default MetaTable<RoleGrant> roleGrants()                   // GRANT ROLE, REVOKE ROLE
  default Optional<Table> bind(TableInfo table)               // the data plane
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
only `scan`. A table of events (`firings`, `refreshes`) need only implement
`insert`. A catalog may reject individual rows it cannot honor, by throwing
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
| `REFRESHES` | `Refresh(schemaName, viewName, requestedAt)` | all |
| `PARTITIONS` | `PartitionInfo(schemaName, tableName, spec, options)` | schema, table, spec |
| `FUNCTIONS` | `FunctionInfo(schemaName, functionName, functionLanguage, definition, comment, options)` | schema, function |
| `TRIGGERS` | `TriggerInfo(schemaName, triggerName, tableSchema, tableName, timing, events, schedule, action, paused, comment, options)` | schema, trigger |
| `FIRINGS` | `Firing(schemaName, triggerName, firedAt, options)` | schema, trigger, time |
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
`MemoryMetaTable` is reusable on its own, to back one table of a catalog.

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
