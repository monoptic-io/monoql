/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.engine;

import io.monoptic.monoql.MonoqlException;
import io.monoptic.monoql.Result;
import io.monoptic.monoql.catalog.AlreadyExistsException;
import io.monoptic.monoql.catalog.Catalog;
import io.monoptic.monoql.catalog.CatalogException;
import io.monoptic.monoql.catalog.CatalogProvider;
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
import org.apache.calcite.sql.SqlExplain;
import org.apache.calcite.sql.SqlExplainLevel;
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
  private final CatalogRegistry registry;
  private final Settings settings;
  private final CalciteSchema rootSchema;
  private final MetadataSchema metadata;
  private final Lowering lowering = new Lowering(this);
  private int viewDepth;
  /** Stops the statement that is running; replaced for each statement. */
  private volatile AtomicBoolean cancel = new AtomicBoolean();

  public Engine(FrameworkConfig config, List<CatalogProvider> providers) {
    this.config = config;
    this.registry = new CatalogRegistry(providers, this::catalogsChanged);
    this.settings = new Settings(registry);
    this.metadata = new MetadataSchema(registry, settings, this::viewType);
    // Not cached: the catalogs are the source of truth.
    this.rootSchema =
        CalciteSchema.createRootSchema(false, false, "",
            new CatalogSchemas.Root(registry, metadata, this::viewType));
  }

  private void catalogsChanged() {
    settings.validate();
  }

  public void mount(String name, Catalog catalog) {
    registry.mount(name, catalog, null);
  }

  /** Sets the current catalog and schema; either may be null. */
  public void use(String catalog, String schema) {
    settings.update(new Setting(Setting.CATALOG, null),
        new Setting(Setting.CATALOG, catalog));
    settings.update(new Setting(Setting.SCHEMA, null),
        new Setting(Setting.SCHEMA, schema));
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

  public void close() {
    registry.closeAll();
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

  public Result execute(String sql) {
    return execute(parse(sql));
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
      try (Result result = execute(node)) {
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

  private Result execute(SqlNode node) {
    cancel = new AtomicBoolean();
    if (node instanceof SqlExplain explain) {
      return explainResult(explain.getExplicandum());
    }
    final Lowered lowered = lowering.lower(node);
    if (lowered == null) {
      return run(node, null);
    }
    Result result = null;
    for (Step step : lowered.steps()) {
      result = run(parse(step.sql()), step);
    }
    return result;
  }

  /** Runs a query or DML statement.
   *
   * @param step If the statement came from lowering, how strict to be */
  private Result run(SqlNode node, Step step) {
    final QueryPlanner planner = planner();
    final boolean upsert = node instanceof SqlInsert insert
        && insert.isUpsert();
    final SqlNode validated = validate(planner, node);
    final RelRoot root = planner.rel(validated);
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
              planner.typeFactory(), cancel);
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
          relation.delete(relation.row(values));
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
        planner.typeFactory(), cancel);
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
            relation.upsert(row);
          } else {
            relation.insert(row);
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
        relation.update(before.get(i), after.get(i));
        ++count;
      }
      return count;
    case DELETE:
      for (Object[] row : rows) {
        relation.delete(row);
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

  private Result explainResult(SqlNode node) {
    final RelDataTypeFactory typeFactory = planner().typeFactory();
    final RelDataType rowType =
        typeFactory.builder()
            .add("PLAN", SqlTypeName.VARCHAR)
            .build();
    return Result.ofRows(rowType,
        List.<Object[]>of(new Object[] {plan(node)}).iterator(), null);
  }
}
