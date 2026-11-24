# monoql

monoql is a SQL engine built on a fork of [Apache Calcite](https://calcite.apache.org),
maintained by [Monoptic](https://monoptic.io).

It adds three things to Calcite, and takes one away:

- **A broad DDL, DCL and control grammar.** `CREATE`/`DROP`/`ALTER` for
  catalogs, schemas, tables, views, materialized views, functions, triggers,
  roles and users; `GRANT` and `REVOKE`; `PAUSE`, `RESUME`, `FIRE` and
  `REFRESH`; `SHOW`, `USE`, `COMMENT ON`. The syntax follows what Databricks,
  Snowflake, BigQuery and Flink have in common, so that it fits most backends
  without modification.
- **A catalog SPI made of tables.** A catalog is a set of small metadata
  tables (schemas, tables, grants, triggers, ...), each of which can be
  scanned, inserted into, updated and deleted from. Every one is optional, and
  unsupported by default, so an adapter is exactly as large as what its
  backend can do.
- **One metadata model.** Statements are not dispatched to service methods.
  `GRANT` *is* an insert into `METADATA.GRANTS`; `DROP TABLE` *is* a delete
  from `METADATA.TABLES`; `PAUSE TRIGGER` *is* an update of
  `METADATA.TRIGGERS`. The statement is rewritten to the DML, and the DML is
  what runs. Either spelling works, and everything is queryable.
- **No JDBC driver.** Calcite's `jdbc:calcite:` driver is gone from the
  engine. A `Session` parses, plans and executes in-process through Calcite's
  `Frameworks` machinery. JDBC remains only as a way to reach *other*
  databases.

## Try it

You need a JDK 21 installed. Gradle finds it and runs its daemon on it,
whatever `java` is on your path; the shell itself runs on 21 or later.

```
$ ./monoql
monoql 0.1.0-SNAPSHOT  Type !help for commands; end SQL with a semicolon.
MEMORY.MAIN> create table orders (id int not null primary key, customer varchar(20), amount decimal(10, 2));
OK, 1 row affected in 0.412s
MEMORY.MAIN> insert into orders values (1, 'ann', 10.50), (2, 'bob', 20.00);
OK, 2 rows affected in 0.218s
MEMORY.MAIN> grant select on orders to role analyst;
OK, 1 row affected in 0.031s
MEMORY.MAIN> select privilege, grantee from metadata.grants;
┌───────────┬─────────┐
│ PRIVILEGE │ GRANTEE │
├───────────┼─────────┤
│ SELECT    │ ANALYST │
└───────────┴─────────┘
1 row in 0.044s
MEMORY.MAIN> !lower revoke select on orders from role analyst
DELETE FROM "METADATA"."GRANTS"
WHERE "CATALOG_NAME" = 'MEMORY'
  AND "OBJECT_TYPE" = 'TABLE'
  AND "OBJECT_SCHEMA" = 'MAIN'
  AND "OBJECT_NAME" = 'ORDERS'
  AND "PRIVILEGE" = 'SELECT'
  AND "GRANTEE_TYPE" IN ('ROLE', 'PRINCIPAL') AND "GRANTEE" = 'ANALYST';
```

`!lower` prints what a statement is shorthand for. Paste that `DELETE` in and
it does what the `REVOKE` would have done.

From Java:

```java
try (Session session = Monoql.builder().build()) {
  session.execute("create table t (id int, name varchar)");
  session.execute("insert into t values (1, 'a')");
  try (Result result = session.execute("select * from t")) {
    for (Object[] row : result) { ... }
  }
}
```

Rows are produced as the result is read, and can be read once.
`session.cancel()`, from another thread, stops a statement that is running.

`Monoql.builder()` takes catalogs to mount (`.catalog(name, catalog)`), extra
catalog types (`.provider(...)`), a user to act as (`.user(name)`), and a hook
to customize the Calcite `FrameworkConfig` the session plans with
(`.configure(...)`). With a user, statements are held to the grants in
`METADATA.GRANTS`; see [Enforcement](docs/sql.md#enforcement).

## Documentation

- [SQL reference](docs/sql.md): every statement monoql adds, and the DML each
  one stands for.
- [Catalogs](docs/catalogs.md): the metadata tables, and how to write a
  catalog.
- [The shell](docs/shell.md).
- [UPSTREAM.md](UPSTREAM.md): how this fork relates to Apache Calcite, what
  was changed, and how to take upstream changes.

## Layout

New code is under `io.monoptic`; forked code keeps its `org.apache.calcite`
packages and, as far as possible, its text.

| Module | What it is |
|---|---|
| `monoql-catalog` | The catalog API and SPI (`io.monoptic.monoql.catalog`), and an in-memory catalog that implements all of it. |
| `monoql-engine` | The parser extensions, the `METADATA` schema, the lowering of statements to DML, and `Session`. |
| `monoql-jdbc` | A catalog over any JDBC database. An ordinary SPI implementation, in its own jar. |
| `monoql-repl` | The shell. |
| `core`, `linq4j` | Calcite's parser, validator, planner and runtime, with the JDBC driver removed. |
| `legacy-jdbc`, `testkit` | The removed driver, and Calcite's test harness, which uses it. Test-only. |

Calcite's adapters, its `server`, `babel` and `plus` modules, its website and
its release tooling are not here; monoql does not use them.

## Building

```
./gradlew build     # compiles everything and runs every test, in a couple of minutes
./gradlew :monoql-engine:test --tests '*LoweringTest'
```

## Status

This is an early cut. Known gaps, all of them deliberate omissions rather
than silent no-ops:

- Materialized views are stored and can be paused and refreshed, but no
  catalog stores materialized rows; they read as their definition.
- Grants are enforced only for a session built with a user, and only in
  catalogs that have a `GRANTS` table. Anyone can read metadata. The shell
  cannot act as a user.
- Identifiers follow the SQL standard: unquoted names fold to upper case.
- Functions are created from a class name or an opaque body; there is no
  syntax for signatures.

## License

Apache License, Version 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
monoql is a derivative work of Apache Calcite; it is not an Apache Software
Foundation project and is not endorsed by the ASF. "Apache Calcite" and
"Calcite" are trademarks of the ASF.
