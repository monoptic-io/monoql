# monoql and Apache Calcite

monoql is a fork of [Apache Calcite](https://github.com/apache/calcite). This
file records how the two relate, so that upstream changes can keep being
taken. The intended reader is whoever does the next merge, human or agent.

## Where we are

| | |
|---|---|
| Upstream | `https://github.com/apache/calcite.git`, remote `upstream` |
| Base | `upstream/main` at commit `020e0e2cf` (2026-11-20): `calcite-1.42.0` plus 397 commits, unreleased, versioned 1.43.0 |
| Recorded in | `calcite.version` in `gradle.properties` |
| Fork branch | `main` |
| Upstream branch | `upstream-main` tracks `upstream/main` |

The repository has upstream's full history, so `git merge` and `git log
020e0e2cf..upstream/main` work as they would in any fork. The fork began at
`calcite-1.42.0`; there was no later release to merge, so the first merge
took `main`.

## The policy

1. **Less code, honestly described.** monoql keeps the parts of Calcite it
   uses and deletes the rest. Divergence from upstream is acceptable; Calcite
   moves slowly, and a merge does not have to be mechanical.
2. **New code goes in `io.monoptic` packages**, in the `monoql-*` modules
   (or, for the seams in core, `core/src/main/java/io/monoptic`), with a
   Monoptic header. It targets Java 21, except in core, which is Java 8.
3. **Changes to Calcite's files are marked** with a `// monoql:` comment that
   says why, and are listed here. Every unmarked difference is one more
   thing the next merge has to rediscover.
4. **Calcite's tests are the regression suite** for the planner. They stay,
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

`testkit` depends on `legacy-jdbc`, so Calcite's tests, most of which drive
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
| `schema/impl/ViewTableMacro.java`, `schema/Schemas.java` | A view is analyzed with a context built on the spot (`Schemas.analyzeView` without a connection: default configuration, a fresh type factory), where upstream used a connection it kept for the purpose. |
| `adapter/clone/CloneSchema.java`, `adapter/jdbc/JdbcTable.java` | `CloneSchema` reads its source tables through `Linq4j.DEFAULT_PROVIDER`, and a JDBC table no longer assumes the provider is a connection. |

`LegacyJdbc` finds the driver with `ServiceLoader` if `legacy-jdbc` is on the
class path, and otherwise throws `UnsupportedOperationException` with an
explanation. Only two things still ask it for anything: a statement prepared
through `Frameworks` that wants the connection it runs on, and the old
`"metadata"` schema.

### Lattices and the materialization service were removed

These were written in terms of a connection, and monoql has its own
materialized views (rows kept by a catalog, refreshed by DML; see
`docs/sql.md`). Removed from core, with their tests:

- `org.apache.calcite.materialize` (lattices, tiles, the lattice suggester,
  `MaterializationService`), `org.apache.calcite.profile` and
  `org.apache.calcite.statistic`, which served them.
- `schema/impl/MaterializedViewTable`, `schema/impl/StarTable`,
  `plan/RelOptLattice`, `prepare/CalciteMaterializer`,
  `AggregateStarTableRule`, `AggregateProjectStarTableRule`, and
  `model/JsonLattice`, `JsonMaterialization`, `JsonTile`, `JsonMeasure`.

What that changed in files that stay, which a merge must keep true:

| Upstream file | Change |
|---|---|
| `tools/Program.java`, `tools/Programs.java` | `Program.run` takes four arguments: there is no list of lattices. |
| `plan/RelOptPlanner.java` and its implementations | No `addLattice` or `getLattice`. |
| `plan/RelOptMaterialization.java`, `plan/RelOptMaterializations.java` | No star table: the constructor takes three arguments, and `tryUseStar` and `useLattices` are gone. |
| `prepare/Prepare.java`, `prepare/CalcitePrepareImpl.java` | `optimize(root)` registers no materializations or lattices; `Prepare.Materialization` is gone. |
| `jdbc/CalciteSchema.java` (and `Caching`/`SimpleCalciteSchema`), `schema/SchemaPlus.java`, `schema/Schemas.java` | No lattice map, `LatticeEntry`, `add(String, Lattice)`, or `FunctionEntry.isMaterialization`. |
| `model/JsonSchema.java` and subclasses, `model/ModelHandler.java` | A model has no `lattices`, `materializations` or `autoLattice`. |
| `tools/FrameworkConfig.java`, `tools/Frameworks.java` | No `isEvolveLattice` or `getStatisticProvider`. |
| `rel/rules/CoreRules.java`, `plan/RelOptRules.java` | The two star-table rules are gone from the rule lists. |
| `legacy-jdbc`: `CalciteConnectionImpl`, `Driver`, `CalciteMetaImpl` | No `init()` to define lattice tiles; `getFunctions` does not filter out materialized views. |

The planner's own rewriting of queries to use materialized views
(`RelOptMaterialization`, `SubstitutionVisitor`, `rel/rules/materialize`)
never needed a connection and is still there, with its tests; monoql does
not use it. `MaterializedViewTester`, in core's tests, populates its tables
with the driver, as `MaterializationService` used to.

Tests removed with the code: `LatticeTest`, `LatticeSuggesterTest`,
`MaterializationTest`, `ProfilerTest`, `SqlStatisticProviderTest`, the
lattice model files, and the handful of cases elsewhere (`JdbcTest`,
`ModelTest`, `FoodmartTest`) that defined a lattice or a materialization in
a model. `HrFKUKSchema`, which was nested in `MaterializationTest`, is now a
class of its own.

Still to do, if wanted: core depends on Avatica (`avatica-core`) for utility
classes (`ByteString`, `TimeUnit`, `ColumnMetaData`, ...) in about 150 files.
That has not been touched. Avatica registers its own *remote* driver
(`jdbc:avatica:remote:`), which is unrelated to `jdbc:calcite:`.

### What was removed

Everything monoql does not use:

- The adapters (`arrow`, `cassandra`, `druid`, `elasticsearch`, `file`,
  `geode`, `innodb`, `kafka`, `mongodb`, `pig`, `redis`, `spark`, `splunk`),
  and `example`, `piglet`, `plus` and `ubenchmark`.
- `server` (upstream's DDL parser and executor) and `babel` (its lenient
  parser). monoql's grammar is its own: `monoql-engine/src/main/codegen`
  extends the same core `Parser.jj` template that those two did.
- `site/` (the website and reference docs), `release/`, the GitHub and
  Jenkins CI configuration, `.asf.yaml`, and the plain-text `README`.
- `sqlline`, `sqlsh` and their tests: clients of `jdbc:calcite:`. `./monoql`
  replaces them.
- Two tests in core that tested removed things: `SqlLineTest`, and
  `DocumentationTest`, which checked the website's SQL reference.
- The JDBC integration-test tasks in core (`integTestH2` and friends).
- `AGENTS.md` and `SECURITY.md`, which describe upstream's process, and the
  `asmchecker` build plugin (a bytecode check wired into upstream's build).

### The build was rewritten

Upstream's build is about 1,100 lines, much of it for publishing Apache
releases: vote staging, Nexus, signing, RAT licence audits, Sonar, build
scans, and half a dozen optional static analysers. None of that applies
here. `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`,
`core/build.gradle.kts` and `buildSrc`'s build files are monoql's own, and
small. What they keep from upstream, because the code depends on it:

- the FMPP and JavaCC tasks in `buildSrc`, which generate the parsers;
- the test settings (a Turkish locale, UTC, concurrent execution, the
  `slow` tag excluded);
- the Java 8 language level for forked modules, and `-Werror`;
- `bom/build.gradle.kts`, pruned to the libraries still in use.

Upstream's checkstyle, autostyle and forbidden-API rules are gone. There is
no style gate on forked code.

Also:

- Artifacts are published under `io.monoptic.monoql`, never under
  `org.apache.calcite`, because they are not Apache's. Forked modules keep
  their artifact names (`calcite-core`, `calcite-linq4j`).
- `.github/workflows/build.yml` is monoql's only CI: it runs `./gradlew build`
  on JDK 21. Upstream's workflows under `.github` stay deleted.
- `gradle/gradle-daemon-jvm.properties` pins the Gradle daemon to JDK 21,
  since Gradle 8 cannot run on 25.
- `CalciteDriverVersion`, which upstream generates from the build version,
  is a plain file in `legacy-jdbc` holding the base version.
- `NOTICE` has a Monoptic preamble above upstream's text.

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
   (<https://calcite.apache.org/docs/history.html>) for API changes and
   removals.
3. `git merge <tag>` into a branch off `main`.
4. Resolve conflicts, by kind:
   - **Deleted modules and files** (everything under "What was removed").
     Upstream will have changed them; git reports modify/delete conflicts.
     Keep them deleted: `git rm`. If upstream *added* a module, it arrives
     without conflict; delete it unless monoql wants it.
   - **Build files.** Keep ours. Then read upstream's diff for anything the
     code needs: a new dependency or version bump (apply it to `bom` and
     `gradle.properties`), a new generated source, a new test setting.
     Git may also "move" files that upstream added in a deleted module into
     `legacy-jdbc` (a file-location conflict); delete those too.
   - **The core files listed above.** The comment marked `monoql:` says what
     has to stay true. If upstream has rewritten the code around it,
     re-derive the change from its purpose rather than forcing the old diff
     onto the new code.
5. Look for **new uses of the driver in core**, which will not conflict but
   will break the build or `NoDriverTest`:
   ```
   grep -rn 'jdbc:calcite\|DriverManager\|CalciteMetaImpl\|CalciteConnectionImpl' core/src/main
   ```
   The only acceptable hits are in comments. New code that needs a
   connection goes through `LegacyJdbc`; better, rework it not to need one.
6. If upstream touched a file that now lives in `legacy-jdbc`, git usually
   follows the rename. Check that it did.
7. Look for core tests that depend on something removed (a deleted module, a
   file under `site/`). Delete them and list them above.
8. Update `calcite.version` in `gradle.properties`, the version in
   `legacy-jdbc`'s `CalciteDriverVersion`, and "Where we are" above.
9. `./gradlew build`. At the base that is 19,713 Calcite tests (core,
   linq4j, testkit) and 232 monoql tests, with no failures. Compare any
   failure against upstream's own results for the tag before assuming the
   merge caused it.
