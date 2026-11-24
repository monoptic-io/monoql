/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.engine;

import io.monoptic.monoql.Identity;
import io.monoptic.monoql.MonoqlException;
import io.monoptic.monoql.Prepared;
import io.monoptic.monoql.Result;
import io.monoptic.monoql.catalog.AlreadyExistsException;
import io.monoptic.monoql.catalog.Catalog;
import io.monoptic.monoql.catalog.CatalogException;
import io.monoptic.monoql.catalog.CatalogProvider;
import io.monoptic.monoql.catalog.Key;
import io.monoptic.monoql.catalog.MaterializedViewInfo;
import io.monoptic.monoql.catalog.NotFoundException;
import io.monoptic.monoql.catalog.UnsupportedException;
import io.monoptic.monoql.catalog.WritableTable;
import io.monoptic.monoql.engine.Lowered.Step;

import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.linq4j.Enumerator;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.rel.core.TableModify;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.runtime.CalciteContextException;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlDelete;
import org.apache.calcite.sql.SqlExplain;
import org.apache.calcite.sql.SqlExplainLevel;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlInsert;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.dialect.CalciteSqlDialect;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.tools.FrameworkConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** The engine behind a {@link io.monoptic.monoql.Session}.
 *
 * <p>Every statement takes the same road: parse; if it is DDL, DCL or a
 * control command, {@link Lowering lower} it to DML on the {@code METADATA}
 * schema; validate; convert to relational algebra; optimize; run. DML on a
 * metadata table ends in calls on the catalog SPI. */
public final class Engine implements Lowering.Context {
  private final FrameworkConfig config;
  private final Identity identity;
  private final String user;
  private final CatalogRegistry registry;
  private final boolean ownsCatalogs;
  private final Runnable catalogsChanged = () -> settings().validate();
  private final Authorizer authorizer;
  private final Settings settings;
  private final CalciteSchema rootSchema;
  private final MetadataSchema metadata;
  private final Lowering lowering = new Lowering(this);
  private int viewDepth;
  /** Stops the statement that is running; replaced for each statement. */
  private volatile AtomicBoolean cancel = new AtomicBoolean();
  /** The values of the dynamic parameters of the statement being run. */
  private List<Object> bound = List.of();

  /** Creates an engine with catalogs of its own.
   *
   * @param identity Who the session acts as, or null. With an identity,
   *     statements are checked against the grants of each catalog; see
   *     {@link Authorizer} */
  public Engine(FrameworkConfig config, List<CatalogProvider> providers,
      Identity identity) {
    this(config, new CatalogRegistry(providers), true, identity);
  }

  private Engine(FrameworkConfig config, CatalogRegistry registry,
      boolean ownsCatalogs, Identity identity) {
    this.config = config;
    this.identity = identity;
    this.user = identity == null ? null : identity.user();
    this.registry = registry;
    this.ownsCatalogs = ownsCatalogs;
    registry.addListener(catalogsChanged);
    this.authorizer = new Authorizer(identity, registry);
    this.settings = new Settings(registry, identity);
    this.metadata = new MetadataSchema(registry, settings, this::viewType);
    // Not cached: the catalogs are the source of truth.
    this.rootSchema =
        CalciteSchema.createRootSchema(false, false, "",
            new CatalogSchemas.Root(registry, metadata, this::viewType));
  }

  /** Creates an engine over the same catalogs as this one, acting as
   * someone else, and starting in the same catalog and schema. Catalogs
   * mounted or dropped through either are seen by both; closing the new
   * engine does not close them. */
  public Engine as(Identity identity) {
    final Engine engine = new Engine(config, registry, false, identity);
    engine.use(settings.catalog(), settings.schema());
    return engine;
  }

  public void mount(String name, Catalog catalog) {
    registry.mount(name, catalog, null);
  }

  /** Sets the current catalog and schema; either may be null. Both are
   * set, or, if either does not exist, neither. */
  public void use(String catalog, String schema) {
    final String previousCatalog = settings.catalog();
    final String previousSchema = settings.schema();
    try {
      set(Setting.CATALOG, catalog);
      set(Setting.SCHEMA, schema);
    } catch (RuntimeException e) {
      set(Setting.CATALOG, previousCatalog);
      set(Setting.SCHEMA, previousSchema);
      throw e;
    }
  }

  private void set(String name, String value) {
    settings.update(new Setting(name, null), new Setting(name, value));
  }

  /** Who the session acts as; null if nobody. */
  public Identity identity() {
    return identity;
  }

  public String currentCatalog() {
    return settings.catalog();
  }

  public String currentSchema() {
    return settings.schema();
  }

  public SchemaPlus rootSchema() {
    return rootSchema.plus();
  }

  public FrameworkConfig config() {
    return config;
  }

  private Settings settings() {
    return settings;
  }

  public void close() {
    registry.removeListener(catalogsChanged);
    if (ownsCatalogs) {
      registry.closeAll();
    }
  }

  // -- Lowering.Context -----------------------------------------------------

  @Override public String catalog() {
    return settings.catalog();
  }

  @Override public String schema() {
    return settings.schema();
  }

  @Override public Catalog catalog(String name) {
    return registry.catalog(name);
  }

  @Override public Lowering.Qualified qualify(SqlNode query) {
    final QueryPlanner planner = planner();
    final SqlNode validated = validate(planner, query);
    if (authorizer.enabled()) {
      // A view lets others read what its definer could; so the definer
      // must be able to. Converting the query finds what it reads.
      planner.rel(validated);
      authorize(planner, null);
    }
    return new Lowering.Qualified(
        validated.toSqlString(CalciteSqlDialect.DEFAULT).getSql(),
        planner.rowType(validated));
  }

  // -- Planning -------------------------------------------------------------

  /** Default paths for unqualified names: the current schema, then the
   * current catalog. (The root is always searched last.) */
  private List<List<String>> paths() {
    final List<List<String>> paths = new ArrayList<>();
    final String catalog = settings.catalog();
    final String schema = settings.schema();
    if (catalog != null) {
      if (schema != null) {
        paths.add(List.of(catalog, schema));
      }
      paths.add(List.of(catalog));
    }
    return paths;
  }

  private QueryPlanner planner() {
    return new QueryPlanner(config, rootSchema, paths());
  }

  private SqlNode parse(String sql) {
    try {
      return SqlParser.create(sql, config.getParserConfig()).parseStmt();
    } catch (SqlParseException e) {
      throw new MonoqlException(e.getMessage(), e);
    }
  }

  private static SqlNode validate(QueryPlanner planner, SqlNode node) {
    try {
      return planner.validate(node);
    } catch (CalciteContextException e) {
      throw new MonoqlException(message(e), e);
    }
  }

  /** "From line 1, column 8 to line 1, column 9: Table 'X' not found";
   * the positions are useful, the exception class names are not. */
  private static String message(CalciteContextException e) {
    final String m = e.getMessage();
    return m == null ? String.valueOf(e.getCause()) : m;
  }

  /** Derives the row type of a view's definition. */
  private RelDataType viewType(String sql, List<String> schemaPath) {
    if (++viewDepth > 64) {
      viewDepth = 0;
      throw new CatalogException(
          "A view is nested too deeply; is it defined in terms of itself?");
    }
    try {
      final QueryPlanner planner =
          new QueryPlanner(config, rootSchema, List.of(schemaPath));
      final SqlNode node;
      try {
        node = SqlParser.create(sql, config.getParserConfig()).parseQuery();
      } catch (SqlParseException e) {
        throw new CatalogException("Invalid view definition: "
            + e.getMessage(), e);
      }
      return planner.rowType(validate(planner, node));
    } finally {
      viewDepth = Math.max(0, viewDepth - 1);
    }
  }

  // -- Execution ------------------------------------------------------------

  /** Executes one statement.
   *
   * @param parameters One value for each {@code ?} in the statement */
  public Result execute(String sql, Object... parameters) {
    return execute(parse(sql), parameters);
  }

  /** Checks a statement without running it, and returns what it will
   * return and what parameters it takes. */
  public Prepared prepare(String sql) {
    final RelDataTypeFactory typeFactory = planner().typeFactory();
    final RelDataType none = typeFactory.builder().build();
    SqlNode node = parse(sql);
    if (node instanceof SqlExplain) {
      return new Prepared(sql, planType(typeFactory), none,
          parameters -> execute(sql, parameters));
    }
    final Lowered lowered = lowering.lower(node);
    RelDataType parameterType = none;
    if (lowered != null) {
      // What the statement returns is what its last step returns. A step
      // that is not a query may depend on the steps before it, and cannot
      // be checked until they have run.
      final List<Step> steps = lowered.steps();
      node = parse(steps.get(steps.size() - 1).sql());
      if (!node.isA(SqlKind.QUERY)) {
        return new Prepared(sql, null, none,
            parameters -> execute(sql, parameters));
      }
    }
    final QueryPlanner planner = planner();
    final SqlNode validated = validate(planner, node);
    if (lowered == null) {
      parameterType = planner.parameterRowType(validated);
    }
    return new Prepared(sql,
        validated.isA(SqlKind.DML) ? null : planner.rowType(validated),
        parameterType, parameters -> execute(sql, parameters));
  }

  /** Executes statements separated by semicolons, handing each result to
   * {@code sink} as it is produced, and closing it when the sink returns. */
  public void executeScript(String sql, Consumer<Result> sink) {
    final List<SqlNode> nodes;
    try {
      nodes = SqlParser.create(sql, config.getParserConfig()).parseStmtList()
          .getList();
    } catch (SqlParseException e) {
      throw new MonoqlException(e.getMessage(), e);
    }
    for (SqlNode node : nodes) {
      try (Result result = execute(node, new Object[0])) {
        sink.accept(result);
      }
    }
  }

  public Lowered lower(String sql) {
    final Lowered lowered = lowering.lower(parse(sql));
    return lowered != null ? lowered : Lowered.of(sql);
  }

  /** Asks the statement that is running, or whose rows are being read, to
   * stop; it fails with {@link java.util.concurrent.CancellationException}.
   * May be called from any thread. */
  public void cancel() {
    cancel.set(true);
  }

  private Result execute(SqlNode node, Object[] parameters) {
    cancel = new AtomicBoolean();
    if (node instanceof SqlExplain explain) {
      return explainResult(explain.getExplicandum());
    }
    if (node.isA(SqlKind.QUERY)) {
      // Nothing to undo; and its rows are read after this returns.
      return run(node, null, parameters);
    }
    // Anything else changes something, perhaps in several steps, each of
    // them perhaps several calls on a catalog: all of it, or none.
    return registry.atomically(() -> {
      final Lowered lowered = lowering.lower(node);
      if (lowered == null) {
        return run(node, null, parameters);
      }
      if (parameters.length > 0) {
        throw new MonoqlException("Parameters can be given to queries and to"
            + " INSERT, UPDATE and DELETE, but not to other statements");
      }
      Result result = null;
      Step skipped = null;
      for (Step step : lowered.steps()) {
        final SqlNode stepNode = parse(step.sql());
        if (step.fill() && !stored(stepNode)) {
          skipped = step;
          continue;
        }
        result = run(stepNode, step, parameters);
        if (step.ignoreConflict() && result.updateCount() == 0) {
          // IF NOT EXISTS, and it exists: leave it as it is.
          break;
        }
      }
      if (result == null) {
        // REFRESH, of a view whose catalog does not keep its rows.
        throw new UnsupportedException("There is nothing to refresh: the"
            + " catalog does not keep the rows of this materialized view,"
            + " which reads as its definition. This was not run:\n"
            + skipped.sql());
      }
      return result;
    });
  }

  /** Runs a query or DML statement.
   *
   * @param step If the statement came from lowering, how strict to be */
  private Result run(SqlNode node, Step step, Object[] parameters) {
    final QueryPlanner planner = planner();
    final boolean upsert = node instanceof SqlInsert insert
        && insert.isUpsert();
    final SqlNode validated = validate(planner, node);
    bound = Runtime.bind(planner.parameterRowType(validated), parameters);
    final RelRoot root = planner.rel(validated);
    authorize(planner, root);
    if (root.rel instanceof TableModify modify) {
      final MetadataTable table = modify.getTable().unwrap(MetadataTable.class);
      final long count;
      final WritableTable writable =
          modify.getTable().unwrap(WritableTable.class);
      if (table != null) {
        count = modifyMetadata(planner, modify, table.relation, upsert, step);
      } else if (writable != null) {
        count = modifyData(planner, modify, writable);
      } else {
        // Leave it to Calcite: for instance, a JDBC table, where the whole
        // statement is pushed down.
        final List<Object[]> rows = rows(planner, root);
        count = rows.isEmpty() ? 0 : ((Number) rows.get(0)[0]).longValue();
      }
      return Result.ofCount(count == 0 ? nothingAffected(step) : count);
    }
    if (step == null) {
      // The user's own query: hand back a cursor, and read as it is read.
      final Enumerator<Object[]> cursor =
          Runtime.open(planner.optimize(root), rootSchema.plus(),
              planner.typeFactory(), cancel, user, bound);
      return Result.ofRows(root.validatedRowType, iterator(cursor),
          cursor::close);
    }
    // A query that a statement lowered to reads metadata, which is small;
    // and whether it found anything decides what the statement means.
    final List<Object[]> rows = rows(planner, root);
    if (rows.isEmpty()) {
      nothingAffected(step);
    }
    return Result.ofRows(root.validatedRowType, rows.iterator(), null);
  }

  /** Checks that the user may read and write the tables named by the
   * statement that {@code planner} just converted. Metadata tables are not checked here: reading
   * them is open, and writes to them are checked row by row.
   *
   * @param root The statement's plan; null if it will not be run */
  private void authorize(QueryPlanner planner, RelRoot root) {
    if (!authorizer.enabled()) {
      return;
    }
    List<String> target = null;
    String operation = null;
    if (root != null && root.rel instanceof TableModify modify) {
      target = modify.getTable().getQualifiedName();
      operation = modify.getOperation().name();
    }
    for (List<String> name : planner.named()) {
      if (name.size() != 3) {
        continue;
      }
      if (name.equals(target) && isMaterializedView(name)) {
        // Its rows are its owner's to write, however they are written.
        authorizer.checkRows(name.get(0), name.get(1), name.get(2));
      } else {
        authorizer.checkRelation(name.get(0), name.get(1), name.get(2),
            name.equals(target) ? operation : "SELECT");
      }
    }
  }

  /** Reads a cursor a row at a time, and no sooner than asked: asking for
   * a row must not wait for the one after it. */
  private static Iterator<Object[]> iterator(Enumerator<Object[]> cursor) {
    return new Iterator<>() {
      /** Whether there is a row to return; null if not yet known. */
      private Boolean has;

      @Override public boolean hasNext() {
        if (has == null) {
          has = cursor.moveNext();
        }
        return has;
      }

      @Override public Object[] next() {
        if (!hasNext()) {
          throw new NoSuchElementException();
        }
        has = null;
        return cursor.current();
      }
    };
  }

  /** Called when a step found no rows. For a step aimed at one catalog,
   * distinguishes "that catalog cannot do this" (and "no such catalog")
   * from "no such object".
   *
   * @return the number of rows affected after all */
  private long nothingAffected(Step step) {
    if (step == null) {
      return 0;
    }
    if (step.catalog() != null) {
      final Relation<?> relation = metadata.relation(step.table());
      try {
        relation.checkSupported(step.catalog());
      } catch (UnsupportedException e) {
        if (step.doomed() == null) {
          throw e;
        }
        // The catalog cannot list these rows, which is why none was found.
        // It may still be able to delete them; ask it to.
        for (Map<String, Object> values : step.doomed()) {
          relation.delete(authorizer, relation.row(values));
        }
        return step.doomed().size();
      }
    }
    if (step.notFound() != null) {
      throw new NotFoundException(step.notFound());
    }
    return 0;
  }

  private List<Object[]> rows(QueryPlanner planner, RelRoot root) {
    return Runtime.execute(planner.optimize(root), rootSchema.plus(),
        planner.typeFactory(), cancel, user, bound);
  }

  /** The rows a modification applies to. For {@code INSERT}, the new rows;
   * for {@code DELETE}, the doomed rows; for {@code UPDATE}, each existing
   * row followed by the new values of the updated columns. */
  private List<Object[]> affected(QueryPlanner planner, TableModify modify) {
    return rows(planner, RelRoot.of(modify.getInput(), SqlKind.SELECT));
  }

  /** Splits the rows of an {@code UPDATE} into before and after images. */
  private static void images(TableModify modify, List<Object[]> rows,
      List<Object[]> before, List<Object[]> after) {
    final int width = modify.getTable().getRowType().getFieldCount();
    final List<String> columns = modify.getUpdateColumnList();
    final List<String> fieldNames =
        modify.getTable().getRowType().getFieldNames();
    for (Object[] row : rows) {
      final Object[] b = Arrays.copyOf(row, width);
      final Object[] a = b.clone();
      for (int i = 0; i < columns.size(); i++) {
        a[fieldNames.indexOf(columns.get(i))] = row[width + i];
      }
      before.add(b);
      after.add(a);
    }
  }

  /** Applies DML to a table that takes writes from the engine. */
  private long modifyData(QueryPlanner planner, TableModify modify,
      WritableTable table) {
    final List<Object[]> rows = affected(planner, modify);
    switch (modify.getOperation()) {
    case INSERT:
      table.insert(rows);
      break;
    case UPDATE:
      final List<Object[]> before = new ArrayList<>();
      final List<Object[]> after = new ArrayList<>();
      images(modify, rows, before, after);
      table.update(before, after);
      break;
    case DELETE:
      table.delete(rows);
      break;
    default:
      throw new UnsupportedException(modify.getOperation()
          + " is not supported on " + modify.getTable().getQualifiedName());
    }
    return rows.size();
  }

  /** Whether the target of an {@code INSERT} or {@code DELETE} is a
   * materialized view whose catalog keeps its rows. */
  private boolean stored(SqlNode node) {
    final SqlNode target =
        node instanceof SqlInsert insert ? insert.getTargetTable()
            : ((SqlDelete) node).getTargetTable();
    final List<String> name = ((SqlIdentifier) target).names;
    if (!isMaterializedView(name)) {
      return false;
    }
    final Catalog catalog = registry.catalog(name.get(0));
    final MaterializedViewInfo view =
        catalog.materializedViews().get(Key.of(name.get(1), name.get(2)))
            .orElseThrow();
    return CatalogSchemas.stored(catalog, view,
        new ViewTable(view.definition(), view.columnNames(),
            name.subList(0, 2), name, this::viewType)).isPresent();
  }

  /** Whether a name is that of a materialized view. */
  private boolean isMaterializedView(List<String> name) {
    if (name.size() != 3) {
      return false;
    }
    final Catalog catalog = registry.catalog(name.get(0));
    try {
      return catalog != null
          && catalog.materializedViews()
              .get(Key.of(name.get(1), name.get(2))).isPresent();
    } catch (UnsupportedException e) {
      return false;
    }
  }

  /** Applies DML to a metadata table: computes the affected rows with the
   * ordinary query machinery, then hands them to the catalog. */
  private long modifyMetadata(QueryPlanner planner, TableModify modify,
      Relation<?> relation, boolean upsert, Step step) {
    final List<Object[]> rows = affected(planner, modify);
    long count = 0;
    switch (modify.getOperation()) {
    case INSERT:
      for (Object[] row : rows) {
        try {
          if (upsert) {
            relation.upsert(authorizer, row);
          } else {
            relation.insert(authorizer, row);
          }
          ++count;
        } catch (AlreadyExistsException e) {
          if (step == null || !step.ignoreConflict()) {
            throw e;
          }
        }
      }
      return count;
    case UPDATE:
      final List<Object[]> before = new ArrayList<>();
      final List<Object[]> after = new ArrayList<>();
      images(modify, rows, before, after);
      for (int i = 0; i < before.size(); i++) {
        relation.update(authorizer, before.get(i), after.get(i));
        ++count;
      }
      return count;
    case DELETE:
      for (Object[] row : rows) {
        relation.delete(authorizer, row);
        ++count;
      }
      return count;
    default:
      throw new CatalogException(modify.getOperation()
          + " is not supported on " + relation.name());
    }
  }

  // -- EXPLAIN --------------------------------------------------------------

  public String explain(String sql) {
    final SqlNode node = parse(sql);
    final Lowered lowered = lowering.lower(node);
    if (lowered == null) {
      return plan(node);
    }
    final StringBuilder b = new StringBuilder();
    for (Step step : lowered.steps()) {
      b.append(plan(parse(step.sql())));
    }
    return b.toString();
  }

  private String plan(SqlNode node) {
    final QueryPlanner planner = planner();
    final RelRoot root = planner.rel(validate(planner, node));
    authorize(planner, root);
    if (root.rel instanceof TableModify modify
        && modify.getTable().unwrap(MetadataTable.class) != null) {
      // The modification itself is applied by the engine; only its input
      // is planned.
      final RelNode input =
          planner.optimize(RelRoot.of(modify.getInput(), SqlKind.SELECT));
      return "MetadataModify(table=" + modify.getTable().getQualifiedName()
          + ", operation=" + modify.getOperation() + ")\n"
          + "  " + RelOptUtil.toString(input,
              SqlExplainLevel.EXPPLAN_ATTRIBUTES).stripTrailing()
              .replace("\n", "\n  ") + "\n";
    }
    return RelOptUtil.toString(planner.optimize(root),
        SqlExplainLevel.EXPPLAN_ATTRIBUTES);
  }

  private static RelDataType planType(RelDataTypeFactory typeFactory) {
    return typeFactory.builder().add("PLAN", SqlTypeName.VARCHAR).build();
  }

  private Result explainResult(SqlNode node) {
    return Result.ofRows(planType(planner().typeFactory()),
        List.<Object[]>of(new Object[] {plan(node)}).iterator(), null);
  }
}
