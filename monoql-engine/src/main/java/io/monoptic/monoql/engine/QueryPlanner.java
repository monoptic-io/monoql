/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.engine;

import org.apache.calcite.adapter.enumerable.EnumerableCalc;
import org.apache.calcite.adapter.enumerable.EnumerableConvention;
import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.config.CalciteConnectionConfig;
import org.apache.calcite.config.CalciteConnectionConfigImpl;
import org.apache.calcite.config.CalciteConnectionProperty;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.calcite.plan.ConventionTraitDef;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.RelTraitDef;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.plan.volcano.VolcanoPlanner;
import org.apache.calcite.prepare.CalciteCatalogReader;
import org.apache.calcite.prepare.Prepare;
import org.apache.calcite.rel.RelCollationTraitDef;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexProgram;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlOperatorTable;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.util.SqlOperatorTables;
import org.apache.calcite.sql.validate.SqlNameMatchers;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.sql.validate.SqlValidatorUtil;
import org.apache.calcite.sql2rel.RelDecorrelator;
import org.apache.calcite.sql2rel.SqlToRelConverter;
import org.apache.calcite.tools.FrameworkConfig;
import org.apache.calcite.tools.Program;
import org.apache.calcite.tools.Programs;
import org.apache.calcite.tools.RelBuilder;
import org.apache.calcite.util.Pair;

import com.google.common.collect.ImmutableList;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/** Plans one statement: validates it, converts it to relational algebra, and
 * optimizes it into an enumerable plan.
 *
 * <p>Everything here is assembled from a {@link FrameworkConfig}; nothing
 * goes through a JDBC connection. It differs from Calcite's stock
 * {@code PlannerImpl} in that names are resolved against several default
 * paths (the current schema, then the current catalog, then the root), and
 * in that view expansion is depth-limited. */
final class QueryPlanner implements RelOptTable.ViewExpander {
  private static final int MAX_VIEW_DEPTH = 64;

  private final FrameworkConfig config;
  private final CalciteSchema rootSchema;
  private final List<List<String>> paths;
  private final JavaTypeFactory typeFactory;
  private final RelOptPlanner planner;
  private final CalciteConnectionConfig connectionConfig;
  private SqlValidator validator;
  private final Set<List<String>> named = new LinkedHashSet<>();
  private int viewDepth;

  /** Creates a planner.
   *
   * @param config Framework configuration
   * @param rootSchema Root of the schema tree
   * @param paths Default schema paths, most specific first; the root is
   *     always searched last */
  QueryPlanner(FrameworkConfig config, CalciteSchema rootSchema,
      List<List<String>> paths) {
    this.config = config;
    this.rootSchema = rootSchema;
    final List<List<String>> allPaths = new ArrayList<>(paths);
    allPaths.add(ImmutableList.of());
    this.paths = allPaths;
    this.typeFactory = new JavaTypeFactoryImpl(config.getTypeSystem());
    final Properties properties = new Properties();
    properties.setProperty(
        CalciteConnectionProperty.CASE_SENSITIVE.camelName(),
        String.valueOf(config.getParserConfig().caseSensitive()));
    this.connectionConfig = new CalciteConnectionConfigImpl(properties);
    this.planner = new VolcanoPlanner(config.getCostFactory(),
        config.getContext());
    RelOptUtil.registerDefaultRules(planner, false, false);
    planner.setExecutor(config.getExecutor());
    if (config.getTraitDefs() == null) {
      planner.addRelTraitDef(ConventionTraitDef.INSTANCE);
      planner.addRelTraitDef(RelCollationTraitDef.INSTANCE);
    } else {
      for (RelTraitDef<?> def : config.getTraitDefs()) {
        planner.addRelTraitDef(def);
      }
    }
  }

  JavaTypeFactory typeFactory() {
    return typeFactory;
  }

  /** A catalog reader with more than one default path, which can note the
   * tables it is asked for. */
  private static final class Reader extends CalciteCatalogReader {
    private final Set<List<String>> named;

    Reader(CalciteSchema rootSchema, List<List<String>> paths,
        JavaTypeFactory typeFactory, CalciteConnectionConfig config,
        Set<List<String>> named) {
      super(rootSchema,
          SqlNameMatchers.withCaseSensitive(config.caseSensitive()), paths,
          typeFactory, config);
      this.named = named;
    }

    @Override public Prepare.PreparingTable getTable(List<String> names) {
      final Prepare.PreparingTable table = super.getTable(names);
      if (table != null && named != null) {
        named.add(table.getQualifiedName());
      }
      return table;
    }
  }

  private CalciteCatalogReader catalogReader() {
    return new Reader(rootSchema, paths, typeFactory, connectionConfig, null);
  }

  private SqlValidator validator(CalciteCatalogReader catalogReader) {
    final SqlOperatorTable operatorTable =
        SqlOperatorTables.chain(config.getOperatorTable(), catalogReader);
    return SqlValidatorUtil.newValidator(operatorTable, catalogReader,
        typeFactory,
        config.getSqlValidatorConfig()
            .withConformance(config.getParserConfig().conformance())
            .withIdentifierExpansion(true));
  }

  /** Validates a statement. Identifiers in the returned tree are fully
   * qualified. */
  SqlNode validate(SqlNode node) {
    this.validator = validator(catalogReader());
    return validator.validate(node);
  }

  /** Returns the full names of the tables and views named by the statement
   * last converted by {@link #rel}. Tables that it reaches only through a
   * view are not among them: a view is expanded with a reader of its
   * own. */
  Set<List<String>> named() {
    return named;
  }

  /** Returns the types of the dynamic parameters of the statement last
   * validated, as the fields of a row type. */
  RelDataType parameterRowType(SqlNode validated) {
    return validator.getParameterRowType(validated);
  }

  /** Returns the row type of the statement last validated. */
  RelDataType rowType(SqlNode validated) {
    return validator.getValidatedNodeType(validated);
  }

  private SqlToRelConverter converter(SqlValidator validator,
      CalciteCatalogReader catalogReader) {
    final RelOptCluster cluster =
        RelOptCluster.create(planner, new RexBuilder(typeFactory));
    return new SqlToRelConverter(this, validator, catalogReader, cluster,
        config.getConvertletTable(),
        config.getSqlToRelConverterConfig().withTrimUnusedFields(false));
  }

  private static RelRoot finish(SqlToRelConverter converter, RelRoot root) {
    root = root.withRel(converter.flattenTypes(root.rel, true));
    final RelBuilder relBuilder =
        RelBuilder.proto().create(root.rel.getCluster(), null);
    return root.withRel(RelDecorrelator.decorrelateQuery(root.rel, relBuilder));
  }

  /** Converts a validated statement to relational algebra. */
  RelRoot rel(SqlNode validated) {
    named.clear();
    final SqlToRelConverter converter =
        converter(validator,
            new Reader(rootSchema, paths, typeFactory, connectionConfig,
                named));
    return finish(converter, converter.convertQuery(validated, false, true));
  }

  @Override public RelRoot expandView(RelDataType rowType, String queryString,
      List<String> schemaPath, List<String> viewPath) {
    if (++viewDepth > MAX_VIEW_DEPTH) {
      throw new IllegalStateException("View " + viewPath
          + " is nested too deeply; is it defined in terms of itself?");
    }
    try {
      final SqlNode node;
      try {
        node = SqlParser.create(queryString, config.getParserConfig())
            .parseQuery();
      } catch (SqlParseException e) {
        throw new IllegalStateException("View " + viewPath
            + " has an invalid definition: " + e.getMessage(), e);
      }
      final CalciteCatalogReader catalogReader =
          catalogReader().withSchemaPath(schemaPath);
      final SqlValidator viewValidator = validator(catalogReader);
      final SqlToRelConverter converter =
          converter(viewValidator, catalogReader);
      return finish(converter, converter.convertQuery(node, true, false));
    } finally {
      --viewDepth;
    }
  }

  /** Optimizes a plan into the enumerable convention. */
  RelNode optimize(RelRoot root) {
    final RelTraitSet traits =
        root.rel.getCluster().traitSet()
            .replace(EnumerableConvention.INSTANCE)
            .replace(root.collation)
            .simplify();
    final Program program = config.getPrograms().isEmpty()
        ? Programs.standard() : config.getPrograms().get(0);
    final RelNode best =
        program.run(planner, root.rel, traits, ImmutableList.of());
    if (root.isRefTrivial()) {
      return best;
    }
    // The query sorts on, or otherwise computes, columns it does not return.
    // Drop them last, so that the sort survives.
    final RexBuilder rexBuilder = best.getCluster().getRexBuilder();
    final List<RexNode> projects = new ArrayList<>();
    for (int field : Pair.left(root.fields)) {
      projects.add(rexBuilder.makeInputRef(best, field));
    }
    return EnumerableCalc.create(best,
        RexProgram.create(best.getRowType(), projects, null,
            root.validatedRowType, rexBuilder));
  }
}
