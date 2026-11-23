# monoql and Apache Calcite

monoql is a fork of [Apache Calcite](https://github.com/apache/calcite). This
file records how the two relate, so that upstream changes can keep being
taken. The intended reader is whoever does the next merge, human or agent.

## Where we are

| | |
|---|---|
| Upstream | `https://github.com/apache/calcite.git`, remote `upstream` |
| Base | tag `calcite-1.42.0` (commit `c01f6b551`) |
| Recorded in | `calcite.version` in `gradle.properties` |
| Fork branch | `main` |
| Upstream branch | `upstream-main` tracks `upstream/main` |

The repository has upstream's full history, so `git merge` and `git log
calcite-1.42.0..` work as they would in any fork.

## The policy

1. **New code goes in `io.monoptic` packages**, in the `monoql-*` modules
   (or, for the seams in core, `core/src/main/java/io/monoptic`). It has a
   Monoptic header, targets Java 21 (except in core, which is Java 8), and is
   exempt from upstream's checkstyle, autostyle and RAT rules.
2. **Upstream code stays where it is and, as far as possible, as it is.** A
   change to an upstream file should be small, and marked with a
   `// monoql:` comment that says why. Staying mergeable is not the goal for
   its own sake; divergence is acceptable when it buys something. But every
   unmarked difference is one more thing the next merge has to rediscover.
3. **Upstream's tests are the regression suite** for the planner. They stay,
   and stay passing.

## What was changed in upstream code

### The JDBC driver was moved out of core

Upstream Calcite is, at bottom, a JDBC driver: `jdbc:calcite:`. monoql does
not want one. The driver and the Avatica-based classes behind it were moved,
unmodified, from `core` to a new module, `legacy-jdbc`:

```
org.apache.calcite.jdbc: Driver, CalciteConnectionImpl, CalciteFactory,
    CalciteJdbc41Factory, CalciteMetaImpl, CalciteMetaColumnFactory[Impl],
    CalciteMetaTableFactory[Impl], CalciteStatement, CalcitePreparedStatement,
    CalciteResultSet, MetadataSchema
org.apache.calcite.tools: RelRunners
META-INF/services/java.sql.Driver
```

`testkit` depends on `legacy-jdbc`, so upstream's tests, most of which drive
Calcite through the driver, run as before. Nothing under `io.monoptic`
depends on it, and `NoDriverTest` in `monoql-engine` checks that the driver
classes are absent from monoql's class path.

Core used the driver in a few places. They were handled as follows; each
edit is marked `// monoql:` or goes through a class in
`io.monoptic.monoql.calcite`.

| Upstream file | Change |
|---|---|
| `tools/Frameworks.java` | `withPrepare` (and so `withPlanner`, and `RelBuilder.create`) opened a `jdbc:calcite:` connection to obtain a planning environment. It now calls `StandalonePrepare`, which builds the environment directly. |
| `jdbc/CalciteSchema.java` | `createRootSchema` no longer refers to `CalciteConnectionImpl.RootSchema` (it has its own copy), and adds the old `"metadata"` schema only if `legacy-jdbc` is present. |
| `schema/impl/MaterializedViewTable.java` | The static `MATERIALIZATION_CONNECTION`, which opened a connection when the class loaded, is gone. Its users call `LegacyJdbc.materializationConnection()`. |
| `schema/impl/ViewTableMacro.java`, `materialize/Lattice.java`, `materialize/MaterializationService.java`, `materialize/*LatticeStatisticProvider.java`, `adapter/clone/CloneSchema.java` | Obtain their connection from `LegacyJdbc`. |
| `core/build.gradle.kts` | The generated driver version comes from `calcite.version`, not the project version. |

`LegacyJdbc` finds the driver with `ServiceLoader` if `legacy-jdbc` is on the
class path, and otherwise throws `UnsupportedOperationException` with an
explanation. So these upstream features **do not work in monoql proper**, by
design, until someone reworks them without a connection: materializations
and lattices, views defined through `ViewTableMacro` (model files, and the
`server` module's `CREATE VIEW`), and `CloneSchema`. monoql has its own views
and does not use any of them.

Still to do, if wanted: core depends on Avatica (`avatica-core`) for utility
classes (`ByteString`, `TimeUnit`, `ColumnMetaData`, ...) in about 150 files.
That has not been touched. Avatica registers its own *remote* driver
(`jdbc:avatica:remote:`), which is unrelated to `jdbc:calcite:`.

### Build

| File | Change |
|---|---|
| `settings.gradle.kts` | Root project is `monoql`; new modules registered. |
| `build.gradle.kts` | Group is `io.monoptic.monoql`; version is `monoql.version`; `monoql-*` modules keep their names as artifact ids (forked modules are still `calcite-*`); `io/monoptic/**` excluded from checkstyle and autostyle; the sqlline class path task is removed. |
| `gradle.properties` | `monoql.version`; `jline.version`. |
| `gradle/gradle-daemon-jvm.properties` | New. Pins the Gradle daemon to JDK 21, since Gradle 8 cannot run on 25. |
| `bom/build.gradle.kts` | JLine. |
| `testkit/build.gradle.kts`, `splunk/build.gradle.kts` | Depend on `legacy-jdbc`. (`SplunkDriver` extends the Calcite driver.) |
| `.ratignore` | Monoptic's files. |
| `sqlline`, `sqlline.bat`, `sqlsh`, `sqlsh.bat` | Deleted. They were clients of `jdbc:calcite:`. `./monoql` replaces them. |
| `README.md` | Rewritten. (`README`, the plain-text one, is upstream's and is used by its release tooling.) |

Artifacts are published under `io.monoptic.monoql`, never under
`org.apache.calcite`, because they are not Apache's.

### Not changed, but superseded

- The `server` module (`SqlDdlParserImpl`, `ServerDdlExecutor`) is upstream's
  DDL. monoql's grammar is separate: `monoql-engine/src/main/codegen` extends
  the same core `Parser.jj` template that `server` and `babel` do.
- The adapters (`cassandra`, `druid`, `elasticsearch`, `file`, `geode`,
  `innodb`, `kafka`, `mongodb`, `pig`, `redis`, `spark`, `splunk`, `arrow`),
  `piglet`, `plus`, `ubenchmark`, `site/`, and upstream's CI and release
  configuration are carried as they are. None is on monoql's path. Whether to
  keep them is an open question; keeping them costs little and keeps merges
  simple.
- Three of those still talk to the driver from main code, and so only work
  with `legacy-jdbc` on the class path: `plus` (`SqlShell`, and the Chinook
  connection provider), `splunk` (`SplunkDriver`), and `ubenchmark`.

## How monoql uses Calcite

Useful when judging whether an upstream change matters:

- **Parser**: `core/src/main/codegen/templates/Parser.jj` and
  `default_config.fmpp`, through the FMPP/JavaCC build plugins in `buildSrc`.
  monoql adds statements through the template's hooks
  (`statementParserMethods`, `createStatementParserMethods`,
  `dropStatementParserMethods`) and reuses its productions
  (`CompoundIdentifier`, `DataType`, `OrderedQueryOrExpr`, `SqlStmt`, ...).
  A change to those hooks or productions needs attention.
- **Planning**: `io.monoptic.monoql.engine.QueryPlanner` is assembled from a
  `FrameworkConfig`: `SqlValidatorUtil.newValidator`, `CalciteCatalogReader`
  (subclassed for multiple default paths), `SqlToRelConverter`,
  `RelDecorrelator`, `VolcanoPlanner`, `Programs.standard()`.
- **Execution**: `EnumerableInterpretable.toBindable`, bound to monoql's own
  `DataContext`.
- **Schemas**: the `Schema`/`Lookup` API, through a non-caching
  `CalciteSchema`.
- **JDBC adapter**: `JdbcSchema`, `JdbcConvention`, for the `jdbc` catalog.

## Taking upstream changes

Patches need not apply mechanically. The point of a merge is to end up with
upstream's improvements and monoql's invariants both intact.

1. `git fetch upstream`, and update `upstream-main`.
2. Choose a target, preferably a release tag. Read its release notes
   (`site/_docs/history.md`) for API changes and removals.
3. `git merge <tag>` into a branch off `main`.
4. Resolve conflicts. They will be in the files listed above. In each, the
   comment marked `monoql:` says what has to stay true. If upstream has
   rewritten the code around it, re-derive the change from its purpose
   rather than forcing the old diff onto the new code.
5. Look for **new uses of the driver in core**, which will not conflict but
   will break the build or `NoDriverTest`:
   ```
   grep -rn 'jdbc:calcite\|DriverManager\|CalciteMetaImpl\|CalciteConnectionImpl' core/src/main
   ```
   The only acceptable hits are in comments. New code that needs a
   connection goes through `LegacyJdbc`; better, rework it not to need one.
6. If upstream touched any file that now lives in `legacy-jdbc`, the change
   arrives as a modification to a file git knows was renamed, and usually
   follows it. Check that it did.
7. Update `calcite.version` in `gradle.properties`, and "Where we are" above.
8. Run everything:
   ```
   ./gradlew :core:test :server:test :babel:test :plus:test :linq4j:test \
       :file:test :example:csv:test :testkit:test \
       :monoql-catalog:test :monoql-engine:test :monoql-jdbc:test :monoql-repl:test
   ./gradlew :core:checkstyleMain :core:autostyleJavaCheck rat
   ```
   At the base, that is 20,223 upstream tests and 185 monoql tests, with no
   failures. Compare failures against upstream's own results for the tag
   before assuming the merge caused them.
