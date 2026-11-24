# CLAUDE.md

Guidance for agents working in this repository.

monoql is a fork of Apache Calcite. Read `README.md` for what it is,
`docs/` for how it behaves, and `UPSTREAM.md` before touching anything under
`org.apache.calcite` or merging from upstream.

## Commands

```
./gradlew build                            # everything, a couple of minutes
./gradlew :monoql-catalog:test :monoql-engine:test :monoql-jdbc:test :monoql-metastore:test :monoql-repl:test
./gradlew :monoql-engine:test --tests '*LoweringTest'
./gradlew :core:test                       # Calcite's suite, about a minute
./monoql -e "show tables"                  # the shell; builds first
```

`./gradlew` can be launched with any recent `java`; its daemon is pinned to
JDK 21, which must be installed.

## Rules

- **New code goes in `io.monoptic` packages**, in the `monoql-*` modules,
  with the Monoptic header. Do not add new classes under
  `org.apache.calcite`.
- **Prefer less code, and say what is true.** Delete what monoql does not
  use rather than carrying it. Do not keep features that look supported but
  are not.
- **Mark changes to Calcite's files** with a `// monoql:` comment saying why,
  and list them in `UPSTREAM.md`, so the next merge knows what must stay
  true.
- **No `jdbc:calcite:`.** Nothing under `io.monoptic` may depend on
  `legacy-jdbc` or `testkit`, or open a Calcite connection. Sessions are
  built from a `FrameworkConfig`. `NoDriverTest` enforces this.
- **No phantom syntax.** Anything the parser accepts must reach a metadata
  row and have an effect. If a clause cannot be honored, reject it in the
  grammar or throw `UnsupportedException`; never parse and ignore. Every new
  statement goes in `ParserTest.roundTrip`, which catches clauses that are
  parsed and dropped.
- **Statements are DML.** A new statement is implemented by lowering it, in
  `Lowering`, to SQL against the `METADATA` schema; not by calling a catalog
  directly. If that needs a new kind of row, add a record and a `MetaTable`
  accessor to `Catalog` (default: unsupported), register it in `MetaTables`,
  and implement it in `MemoryCatalog`. Add the statement to
  `LoweringTest.statementsAndTheirDmlHaveTheSameEffect`.
- **Metadata column names must not be reserved words**
  (`SpiTest.columnNamesAreNotReserved`).
- Keep `docs/sql.md` and `docs/catalogs.md` in step with the grammar and
  the SPI.

## Where things are

- Grammar: `monoql-engine/src/main/codegen/includes/parserImpls.ftl`, with
  keywords and hooks in `config.fmpp`. Parse tree: `io.monoptic.monoql.sql`.
- Lowering: `io.monoptic.monoql.engine.Lowering`.
- Execution: `Engine` (dispatch, metadata DML), `QueryPlanner`, `Runtime`.
- Authorization: `Authorizer` holds the rules; `Engine.authorize` checks
  the tables a statement names, and `Relation.authorize` each metadata row
  it writes. A new way to read or write data must go through one of them.
- SQL view of a record: `RowCodec`.
- SPI: `io.monoptic.monoql.catalog`. The rules between metadata tables:
  `catalog.standard.StandardCatalog`, over a `Storage`. Reference
  implementation: `catalog.memory.MemoryCatalog`, which is that over the
  heap; `monoql-metastore` is that over a database.
