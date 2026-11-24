/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.engine;

import io.monoptic.monoql.catalog.Catalog;
import io.monoptic.monoql.catalog.CatalogException;
import io.monoptic.monoql.catalog.Column;
import io.monoptic.monoql.catalog.DataTypes;
import io.monoptic.monoql.catalog.Grant;
import io.monoptic.monoql.catalog.Key;
import io.monoptic.monoql.catalog.MaterializedViewInfo;
import io.monoptic.monoql.catalog.MetaRow;
import io.monoptic.monoql.catalog.MetaTable;
import io.monoptic.monoql.catalog.NotFoundException;
import io.monoptic.monoql.catalog.TableInfo;
import io.monoptic.monoql.catalog.TriggerInfo;
import io.monoptic.monoql.catalog.UnsupportedException;
import io.monoptic.monoql.engine.Lowered.Step;
import io.monoptic.monoql.sql.ObjectKind;
import io.monoptic.monoql.sql.SqlAlterTable;
import io.monoptic.monoql.sql.SqlColumnDecl;
import io.monoptic.monoql.sql.SqlCommentOn;
import io.monoptic.monoql.sql.SqlControl;
import io.monoptic.monoql.sql.SqlCreateCatalog;
import io.monoptic.monoql.sql.SqlCreateFunction;
import io.monoptic.monoql.sql.SqlCreatePrincipal;
import io.monoptic.monoql.sql.SqlCreateSchema;
import io.monoptic.monoql.sql.SqlCreateTable;
import io.monoptic.monoql.sql.SqlCreateTrigger;
import io.monoptic.monoql.sql.SqlCreateView;
import io.monoptic.monoql.sql.SqlDropObject;
import io.monoptic.monoql.sql.SqlGrant;
import io.monoptic.monoql.sql.SqlShow;
import io.monoptic.monoql.sql.SqlUse;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.SqlCreate;
import org.apache.calcite.sql.SqlDescribeSchema;
import org.apache.calcite.sql.SqlDescribeTable;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlSetOption;
import org.apache.calcite.sql.dialect.CalciteSqlDialect;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.function.Function;

/** Rewrites DDL, DCL and control statements as DML and queries against the
 * {@code METADATA} schema.
 *
 * <p>This is where the two spellings meet. {@code GRANT SELECT ON t TO r} is
 * rewritten to {@code UPSERT INTO METADATA.GRANTS ...}; from there on the two
 * are literally the same statement, taking the same path through the
 * validator, the planner and the catalog SPI. The rewriting is to SQL text,
 * which is exactly what {@code !lower} in the shell prints. */
final class Lowering {
  /** What lowering needs to know about the session. */
  interface Context {
    /** Current catalog, or null. */
    String catalog();

    /** Current schema, or null. */
    String schema();

    /** A mounted catalog, or null. */
    Catalog catalog(String name);

    /** Validates a query against the current defaults. */
    Qualified qualify(SqlNode query);
  }

  /** A validated query.
   *
   * @param sql The query with every name fully qualified, so that it means
   *     the same thing whatever the defaults are when it is next read
   * @param rowType Its row type */
  record Qualified(String sql, RelDataType rowType) {
  }

  /** A resolved name. Fields that do not apply are null. */
  private record Name(String catalog, String schema, String name) {
    @Override public String toString() {
      final StringJoiner j = new StringJoiner(".");
      for (String s : new String[] {catalog, schema, name}) {
        if (s != null) {
          j.add(s);
        }
      }
      return j.toString();
    }
  }

  private static final String M = SqlText.id(MetadataSchema.NAME) + ".";
  private static final String CATALOG_NAME = RowCodec.CATALOG_NAME;

  private final Context context;

  Lowering(Context context) {
    this.context = context;
  }

  /** Lowers a statement; returns null if it is already a query or DML. */
  Lowered lower(SqlNode node) {
    if (node instanceof SqlCreateCatalog n) {
      return createCatalog(n);
    } else if (node instanceof SqlCreateSchema n) {
      return createSchema(n);
    } else if (node instanceof SqlCreateTable n) {
      return createTable(n);
    } else if (node instanceof SqlCreateView n) {
      return createView(n);
    } else if (node instanceof SqlCreateFunction n) {
      return createFunction(n);
    } else if (node instanceof SqlCreateTrigger n) {
      return createTrigger(n);
    } else if (node instanceof SqlCreatePrincipal n) {
      return createPrincipal(n);
    } else if (node instanceof SqlDropObject n) {
      return drop(n);
    } else if (node instanceof SqlGrant n) {
      return grant(n);
    } else if (node instanceof SqlControl n) {
      return control(n);
    } else if (node instanceof SqlAlterTable n) {
      return alterTable(n);
    } else if (node instanceof SqlCommentOn n) {
      return commentOn(n);
    } else if (node instanceof SqlShow n) {
      return show(n);
    } else if (node instanceof SqlUse n) {
      return use(n);
    } else if (node instanceof SqlSetOption n) {
      return setOption(n);
    } else if (node instanceof SqlDescribeTable n) {
      return describeTable(n);
    } else if (node instanceof SqlDescribeSchema n) {
      return describeSchema(n);
    }
    return null;
  }

  // -- Names ----------------------------------------------------------------

  private String currentCatalog() {
    final String catalog = context.catalog();
    if (catalog == null) {
      throw new CatalogException(
          "There is no current catalog; qualify the name, or USE CATALOG");
    }
    return catalog;
  }

  private String currentSchema() {
    final String schema = context.schema();
    if (schema == null) {
      throw new CatalogException(
          "There is no current schema; qualify the name, or USE SCHEMA");
    }
    return schema;
  }

  private static CatalogException tooManyParts(SqlIdentifier id, int max) {
    return new CatalogException("Name " + id + " has more than " + max
        + " parts");
  }

  /** {@code [[catalog.]schema.]name}. */
  private Name object(SqlIdentifier id) {
    final List<String> n = id.names;
    switch (n.size()) {
    case 1:
      return new Name(currentCatalog(), currentSchema(), n.get(0));
    case 2:
      return new Name(currentCatalog(), n.get(0), n.get(1));
    case 3:
      return new Name(n.get(0), n.get(1), n.get(2));
    default:
      throw tooManyParts(id, 3);
    }
  }

  /** {@code [catalog.]schema}. */
  private Name schema(SqlIdentifier id) {
    final List<String> n = id.names;
    switch (n.size()) {
    case 1:
      return new Name(currentCatalog(), n.get(0), null);
    case 2:
      return new Name(n.get(0), n.get(1), null);
    default:
      throw tooManyParts(id, 2);
    }
  }

  /** {@code [catalog.]name}, for things that belong to a catalog rather
   * than a schema: roles and users. */
  private Name scoped(SqlIdentifier id) {
    final List<String> n = id.names;
    switch (n.size()) {
    case 1:
      return new Name(currentCatalog(), null, n.get(0));
    case 2:
      return new Name(n.get(0), null, n.get(1));
    default:
      throw tooManyParts(id, 2);
    }
  }

  private static Name catalog(SqlIdentifier id) {
    if (!id.isSimple()) {
      throw tooManyParts(id, 1);
    }
    return new Name(id.getSimple(), null, null);
  }

  // -- Building blocks ------------------------------------------------------

  /** Where each kind of object lives: its table, and its name column. */
  private record Home(String table, String nameColumn) {
  }

  private static Home home(ObjectKind kind) {
    switch (kind) {
    case CATALOG:
      return new Home("CATALOGS", CATALOG_NAME);
    case SCHEMA:
      return new Home("SCHEMAS", "SCHEMA_NAME");
    case TABLE:
      return new Home("TABLES", "TABLE_NAME");
    case VIEW:
      return new Home("VIEWS", "VIEW_NAME");
    case MATERIALIZED_VIEW:
      return new Home("MATERIALIZED_VIEWS", "VIEW_NAME");
    case FUNCTION:
      return new Home("FUNCTIONS", "FUNCTION_NAME");
    case TRIGGER:
      return new Home("TRIGGERS", "TRIGGER_NAME");
    case ROLE:
      return new Home("ROLES", "ROLE_NAME");
    case USER:
      return new Home("USERS", "USER_NAME");
    case COLUMN:
      return new Home("COLUMNS", "COLUMN_NAME");
    default:
      throw new AssertionError(kind);
    }
  }

  private Name resolve(ObjectKind kind, SqlIdentifier id) {
    switch (kind) {
    case CATALOG:
      return catalog(id);
    case SCHEMA:
      return schema(id);
    case ROLE:
    case USER:
      return scoped(id);
    default:
      return object(id);
    }
  }

  /** The predicate that identifies one object. */
  private static String where(ObjectKind kind, Name name) {
    final StringJoiner j = new StringJoiner(" AND ");
    j.add(SqlText.eq(CATALOG_NAME, name.catalog));
    switch (kind) {
    case CATALOG:
      break;
    case SCHEMA:
      j.add(SqlText.eq("SCHEMA_NAME", name.schema));
      break;
    case ROLE:
    case USER:
      j.add(SqlText.eq(home(kind).nameColumn, name.name));
      break;
    default:
      j.add(SqlText.eq("SCHEMA_NAME", name.schema));
      j.add(SqlText.eq(home(kind).nameColumn, name.name));
      break;
    }
    return j.toString();
  }

  /** Builds {@code INSERT} (or {@code UPSERT}) of rows, each a map from
   * column to SQL expression. */
  private static String insert(boolean upsert, String table,
      List<Map<String, String>> rows) {
    final StringBuilder b = new StringBuilder(upsert ? "UPSERT" : "INSERT")
        .append(" INTO ").append(M).append(SqlText.id(table)).append(" (");
    final StringJoiner columns = new StringJoiner(", ");
    rows.get(0).keySet().forEach(c -> columns.add(SqlText.id(c)));
    b.append(columns).append(")\nVALUES ");
    final StringJoiner tuples = new StringJoiner(",\n  ");
    for (Map<String, String> row : rows) {
      tuples.add("(" + String.join(", ", row.values()) + ")");
    }
    return b.append(tuples).toString();
  }

  private static Lowered create(SqlCreate node, String table,
      Map<String, String> row) {
    return new Lowered(
        List.of(
            new Step(insert(node.getReplace(), table, List.of(row)),
                node.ifNotExists, null)));
  }

  private static String update(String table, String set, String where) {
    return "UPDATE " + M + SqlText.id(table) + "\nSET " + set + "\nWHERE "
        + where;
  }

  private static String delete(String table, String where) {
    return "DELETE FROM " + M + SqlText.id(table) + "\nWHERE " + where;
  }

  /** A step that changes or reads existing rows of one catalog's table.
   *
   * @param notFound Error if no row is affected; null if that is fine */
  private static Lowered targeted(String catalog, String table, String sql,
      String notFound) {
    return targeted(catalog, table, sql, notFound, null);
  }

  /** As {@link #targeted(String, String, String, String)}, for a
   * {@code DELETE} that names its rows; see {@link Step#doomed()}. */
  private static Lowered targeted(String catalog, String table, String sql,
      String notFound, List<Map<String, Object>> doomed) {
    return new Lowered(
        List.of(new Step(sql, false, notFound, catalog, table, doomed, false)));
  }

  private static Map<String, Object> values(Object... columnsAndValues) {
    final Map<String, Object> row = new LinkedHashMap<>();
    for (int i = 0; i < columnsAndValues.length; i += 2) {
      row.put((String) columnsAndValues[i], columnsAndValues[i + 1]);
    }
    return row;
  }

  private static String notFound(ObjectKind kind, Name name) {
    final String k = kind.sql.charAt(0)
        + kind.sql.substring(1).toLowerCase(Locale.ROOT);
    return k + " " + name + " not found";
  }

  private static Map<String, String> row(String... columnsAndValues) {
    final Map<String, String> row = new LinkedHashMap<>();
    for (int i = 0; i < columnsAndValues.length; i += 2) {
      row.put(columnsAndValues[i], columnsAndValues[i + 1]);
    }
    return row;
  }

  private static String type(SqlColumnDecl decl) {
    return decl.type().toSqlString(CalciteSqlDialect.DEFAULT).getSql();
  }

  private static String columns(List<Column> columns) {
    return SqlText.array(columns.stream().map(ColumnText::format).toList());
  }

  private static List<String> names(Iterable<SqlNode> list) {
    final List<String> names = new ArrayList<>();
    for (SqlNode node : list) {
      names.add(((SqlIdentifier) node).getSimple());
    }
    return names;
  }

  // -- CREATE ---------------------------------------------------------------

  private Lowered createCatalog(SqlCreateCatalog n) {
    final Map<String, String> options = new LinkedHashMap<>(n.options);
    String type = null;
    for (String key : n.options.keySet()) {
      if (key.equalsIgnoreCase("type")) {
        type = options.remove(key);
      }
    }
    if (type == null) {
      throw new CatalogException(
          "CREATE CATALOG requires a type, e.g. WITH (type = 'memory')");
    }
    return create(n, "CATALOGS",
        row(CATALOG_NAME, SqlText.str(n.name.getSimple()),
            "TYPE", SqlText.str(type),
            "COMMENT", SqlText.str(n.comment),
            "OPTIONS", SqlText.map(options)));
  }

  private Lowered createSchema(SqlCreateSchema n) {
    final Name name = schema(n.name);
    return create(n, "SCHEMAS",
        row(CATALOG_NAME, SqlText.str(name.catalog),
            "SCHEMA_NAME", SqlText.str(name.schema),
            "COMMENT", SqlText.str(n.comment),
            "OPTIONS", SqlText.map(n.options)));
  }

  private Lowered createTable(SqlCreateTable n) {
    final Name name = object(n.name);
    final List<Column> columns = new ArrayList<>();
    Qualified query = null;
    if (n.query != null) {
      query = context.qualify(n.query);
      columns.addAll(DataTypes.columns(query.rowType));
    } else {
      for (SqlColumnDecl c : n.columns) {
        columns.add(ColumnText.of(c));
      }
    }
    final List<Step> steps = new ArrayList<>();
    steps.add(
        new Step(
            insert(n.getReplace(), "TABLES",
                List.of(
                    row(CATALOG_NAME, SqlText.str(name.catalog),
                        "SCHEMA_NAME", SqlText.str(name.schema),
                        "TABLE_NAME", SqlText.str(name.name),
                        "COLUMNS", columns(columns),
                        "PRIMARY_KEY", SqlText.array(names(n.primaryKey)),
                        "PARTITION_KEYS",
                        SqlText.array(names(n.partitionKeys)),
                        "COMMENT", SqlText.str(n.comment),
                        "OPTIONS", SqlText.map(n.options)))),
            n.ifNotExists, null));
    if (query != null) {
      steps.add(
          new Step("INSERT INTO "
              + SqlText.path(name.catalog, name.schema, name.name) + "\n"
              + query.sql, false, null));
    }
    return new Lowered(steps);
  }

  private Lowered createView(SqlCreateView n) {
    final Name name = object(n.name);
    final Qualified query = context.qualify(n.query);
    final List<String> columnNames = names(n.columnList);
    if (!columnNames.isEmpty()
        && columnNames.size() != query.rowType.getFieldCount()) {
      throw new CatalogException("View " + name + " lists "
          + columnNames.size() + " columns but its query returns "
          + query.rowType.getFieldCount());
    }
    final Map<String, String> row =
        row(CATALOG_NAME, SqlText.str(name.catalog),
            "SCHEMA_NAME", SqlText.str(name.schema),
            "VIEW_NAME", SqlText.str(name.name),
            "COLUMN_NAMES", SqlText.array(columnNames),
            "DEFINITION", SqlText.str(query.sql));
    if (n.materialized) {
      row.put("CONTINUOUS", SqlText.bool(n.continuous));
      row.put("PAUSED", SqlText.bool(false));
    }
    row.put("COMMENT", SqlText.str(n.comment));
    if (n.materialized) {
      row.put("OPTIONS", SqlText.map(n.options));
    }
    if (!n.materialized) {
      return create(n, "VIEWS", row);
    }
    final List<Step> steps =
        new ArrayList<>(create(n, "MATERIALIZED_VIEWS", row).steps());
    if (n.getReplace()) {
      // The view it replaces may have left rows behind, a job that is
      // still adding to them, and a schedule.
      steps.addAll(unschedule(name));
      steps.addAll(stop(name));
      steps.add(empty(name));
    }
    final Step fill = fill(name, query.sql);
    if (n.continuous) {
      // The rows are not put there now, once, but by a job, for good:
      // the same INSERT, which whatever runs the job never finishes.
      steps.add(
          new Step(
              insert(false, "JOBS",
                  List.of(
                      row(CATALOG_NAME, SqlText.str(name.catalog),
                          "SCHEMA_NAME", SqlText.str(name.schema),
                          "JOB_NAME", SqlText.str(name.name),
                          "SUBMITTED_AT", "CURRENT_TIMESTAMP",
                          "SUBMITTED_BY", "CURRENT_USER",
                          "STATEMENT", SqlText.str(fill.sql())))),
              false, null));
    } else {
      steps.add(fill);
    }
    if (n.schedule != null) {
      // A schedule is a trigger: of the view's name, whose action is to
      // refresh the view.
      steps.add(
          new Step(
              insert(false, "TRIGGERS",
                  List.of(
                      row(CATALOG_NAME, SqlText.str(name.catalog),
                          "SCHEMA_NAME", SqlText.str(name.schema),
                          "TRIGGER_NAME", SqlText.str(name.name),
                          "SCHEDULE", SqlText.str(n.schedule),
                          "ACTION", SqlText.str(refresh(name)),
                          "PAUSED", SqlText.bool(false)))),
              false, null));
    }
    return new Lowered(steps);
  }

  /** What the trigger that refreshes a view on a schedule does. */
  private static String refresh(Name name) {
    return "REFRESH MATERIALIZED VIEW "
        + SqlText.path(name.catalog, name.schema, name.name);
  }

  /** Whether a materialized view has such a trigger: one of its name,
   * that does that. */
  private boolean scheduled(Name name) {
    try {
      return find(name, Catalog::triggers)
          .map(t -> t.action().equals(refresh(name))).orElse(false);
    } catch (UnsupportedException e) {
      return false;
    }
  }

  private static String triggerOf(Name name) {
    return where(ObjectKind.TRIGGER, name) + " AND "
        + SqlText.eq("ACTION", refresh(name));
  }

  /** The steps that remove the schedule of a materialized view, if it has
   * one. */
  private List<Step> unschedule(Name name) {
    return scheduled(name)
        ? List.of(new Step(delete("TRIGGERS", triggerOf(name)), false, null))
        : List.of();
  }

  /** The steps that cancel the job that keeps a materialized view up to
   * date, if it is such a view: the jobs of its name that insert into
   * it. */
  private List<Step> stop(Name name) {
    if (!find(name, Catalog::materializedViews)
        .map(MaterializedViewInfo::continuous).orElse(false)) {
      return List.of();
    }
    final String prefix =
        "INSERT INTO " + SqlText.path(name.catalog, name.schema, name.name);
    return List.of(
        new Step(
            delete("JOBS",
                SqlText.eq(CATALOG_NAME, name.catalog) + " AND "
                    + SqlText.eq("SCHEMA_NAME", name.schema) + " AND "
                    + SqlText.eq("JOB_NAME", name.name) + "\n  AND SUBSTRING("
                    + SqlText.id("STATEMENT") + ", 1, " + prefix.length()
                    + ") = " + SqlText.str(prefix)),
            false, null));
  }

  /** The step that removes the rows of a materialized view. */
  private static Step empty(Name name) {
    return Step.fill("DELETE FROM "
        + SqlText.path(name.catalog, name.schema, name.name));
  }

  /** The step that gives a materialized view the rows of its query. */
  private static Step fill(Name name, String query) {
    return Step.fill("INSERT INTO "
        + SqlText.path(name.catalog, name.schema, name.name) + "\n" + query);
  }

  private Lowered createFunction(SqlCreateFunction n) {
    final Name name = object(n.name);
    return create(n, "FUNCTIONS",
        row(CATALOG_NAME, SqlText.str(name.catalog),
            "SCHEMA_NAME", SqlText.str(name.schema),
            "FUNCTION_NAME", SqlText.str(name.name),
            "FUNCTION_LANGUAGE", SqlText.str(n.language),
            "DEFINITION", SqlText.str(n.definition),
            "COMMENT", SqlText.str(n.comment),
            "OPTIONS", SqlText.map(n.options)));
  }

  private Lowered createTrigger(SqlCreateTrigger n) {
    final Name name = object(n.name);
    Name table = null;
    if (n.table != null) {
      table = object(n.table);
      if (!table.catalog.equals(name.catalog)) {
        throw new CatalogException("Trigger " + name + " and its table "
            + table + " must be in the same catalog");
      }
    }
    return create(n, "TRIGGERS",
        row(CATALOG_NAME, SqlText.str(name.catalog),
            "SCHEMA_NAME", SqlText.str(name.schema),
            "TRIGGER_NAME", SqlText.str(name.name),
            "TABLE_SCHEMA", SqlText.str(table == null ? null : table.schema),
            "TABLE_NAME", SqlText.str(table == null ? null : table.name),
            "TIMING", SqlText.str(n.timing),
            "EVENTS", SqlText.array(n.events),
            "SCHEDULE", SqlText.str(n.schedule),
            "ACTION", SqlText.str(n.action),
            "PAUSED", SqlText.bool(false),
            "COMMENT", SqlText.str(n.comment),
            "OPTIONS", SqlText.map(n.options)));
  }

  private Lowered createPrincipal(SqlCreatePrincipal n) {
    final Name name = scoped(n.name);
    final Home home = home(n.kind);
    return create(n, home.table,
        row(CATALOG_NAME, SqlText.str(name.catalog),
            home.nameColumn, SqlText.str(name.name),
            "COMMENT", SqlText.str(n.comment),
            "OPTIONS", SqlText.map(n.options)));
  }

  // -- DROP, COMMENT --------------------------------------------------------

  private Lowered drop(SqlDropObject n) {
    final Name name = resolve(n.kind, n.name);
    final Home home = home(n.kind);
    final Map<String, Object> doomed = values(CATALOG_NAME, name.catalog);
    if (name.schema != null) {
      doomed.put("SCHEMA_NAME", name.schema);
    }
    if (name.name != null) {
      doomed.put(home.nameColumn, name.name);
    }
    final Lowered drop =
        targeted(name.catalog, home.table,
            delete(home.table, where(n.kind, name)),
            n.ifExists ? null : notFound(n.kind, name),
            n.kind == ObjectKind.ROLE || n.kind == ObjectKind.USER
                ? List.of(doomed) : null);
    if (n.kind != ObjectKind.MATERIALIZED_VIEW) {
      return drop;
    }
    // Its schedule and its job go first, while the view still says it
    // has a job.
    final List<Step> steps = new ArrayList<>(unschedule(name));
    steps.addAll(stop(name));
    steps.addAll(drop.steps());
    return new Lowered(steps);
  }

  private Lowered commentOn(SqlCommentOn n) {
    final String set = SqlText.id("COMMENT") + " = "
        + (n.comment == null ? "CAST(NULL AS VARCHAR)"
            : SqlText.str(n.comment));
    if (n.kind == ObjectKind.COLUMN) {
      final List<String> names = n.name.names;
      if (names.size() < 2) {
        throw new CatalogException("Column " + n.name
            + " must be qualified with its table");
      }
      final Name table = object(n.name.getComponent(0, names.size() - 1));
      final String column = names.get(names.size() - 1);
      return targeted(table.catalog, "COLUMNS",
          update("COLUMNS", set,
              where(ObjectKind.TABLE, table) + " AND "
                  + SqlText.eq("COLUMN_NAME", column)),
          "Column " + table + "." + column + " not found");
    }
    final Name name = resolve(n.kind, n.name);
    return targeted(name.catalog, home(n.kind).table,
        update(home(n.kind).table, set, where(n.kind, name)),
        notFound(n.kind, name));
  }

  // -- GRANT, REVOKE --------------------------------------------------------

  private Lowered grant(SqlGrant n) {
    if (n.isRoleGrant()) {
      return grantRole(n);
    }
    final Name object = resolve(n.objectKind, n.object);
    // A grant on a schema names the schema in OBJECT_SCHEMA and has no
    // OBJECT_NAME; a grant on a catalog has neither.
    final String objectSchema = object.schema;
    final String objectName = object.name;
    if (n.action == SqlGrant.Action.GRANT) {
      final List<Map<String, String>> rows = new ArrayList<>();
      for (String privilege : n.items) {
        for (SqlGrant.Grantee g : n.grantees) {
          rows.add(
              row(CATALOG_NAME, SqlText.str(object.catalog),
                  "PRIVILEGE", SqlText.str(privilege),
                  "OBJECT_TYPE", SqlText.str(n.objectKind.sql),
                  "OBJECT_SCHEMA", SqlText.str(objectSchema),
                  "OBJECT_NAME", SqlText.str(objectName),
                  "GRANTEE_TYPE", SqlText.str(g.type()),
                  "GRANTEE", SqlText.str(g.name()),
                  "GRANTABLE", SqlText.bool(n.grantOption)));
        }
      }
      // Granting again is not an error; it restates the grant.
      return Lowered.of(insert(true, "GRANTS", rows));
    }
    final StringJoiner where = new StringJoiner("\n  AND ");
    where.add(SqlText.eq(CATALOG_NAME, object.catalog));
    where.add(SqlText.eq("OBJECT_TYPE", n.objectKind.sql));
    where.add(SqlText.eq("OBJECT_SCHEMA", objectSchema));
    where.add(SqlText.eq("OBJECT_NAME", objectName));
    if (!n.items.contains("ALL PRIVILEGES")) {
      // REVOKE ALL removes whatever privileges there are.
      where.add(in("PRIVILEGE", n.items));
    }
    where.add(grantees(n.grantees));
    // Revoking something that was never granted is not an error.
    if (n.action == SqlGrant.Action.REVOKE_GRANT_OPTION) {
      return targeted(object.catalog, "GRANTS",
          update("GRANTS", SqlText.id("GRANTABLE") + " = FALSE",
              where.toString()),
          null);
    }
    final List<Map<String, Object>> doomed = new ArrayList<>();
    for (String privilege : n.items) {
      for (SqlGrant.Grantee g : n.grantees) {
        doomed.add(
            values(CATALOG_NAME, object.catalog, "PRIVILEGE", privilege,
                "OBJECT_TYPE", n.objectKind.sql,
                "OBJECT_SCHEMA", objectSchema, "OBJECT_NAME", objectName,
                "GRANTEE_TYPE", g.type(), "GRANTEE", g.name()));
      }
    }
    return targeted(object.catalog, "GRANTS",
        delete("GRANTS", where.toString()), null, doomed);
  }

  private Lowered grantRole(SqlGrant n) {
    final String catalog = currentCatalog();
    if (n.action == SqlGrant.Action.GRANT) {
      final List<Map<String, String>> rows = new ArrayList<>();
      for (String role : n.items) {
        for (SqlGrant.Grantee g : n.grantees) {
          rows.add(
              row(CATALOG_NAME, SqlText.str(catalog),
                  "ROLE_NAME", SqlText.str(role),
                  "GRANTEE_TYPE", SqlText.str(g.type()),
                  "GRANTEE", SqlText.str(g.name())));
        }
      }
      return Lowered.of(insert(true, "ROLE_GRANTS", rows));
    }
    final List<Map<String, Object>> doomed = new ArrayList<>();
    for (String role : n.items) {
      for (SqlGrant.Grantee g : n.grantees) {
        doomed.add(
            values(CATALOG_NAME, catalog, "ROLE_NAME", role,
                "GRANTEE_TYPE", g.type(), "GRANTEE", g.name()));
      }
    }
    return targeted(catalog, "ROLE_GRANTS",
        delete("ROLE_GRANTS",
            SqlText.eq(CATALOG_NAME, catalog) + "\n  AND "
                + in("ROLE_NAME", n.items) + "\n  AND "
                + grantees(n.grantees)),
        null, doomed);
  }

  private static String in(String column, List<String> values) {
    if (values.size() == 1) {
      return SqlText.eq(column, values.get(0));
    }
    final StringJoiner j =
        new StringJoiner(", ", SqlText.id(column) + " IN (", ")");
    values.forEach(v -> j.add(SqlText.str(v)));
    return j.toString();
  }

  /** The predicate that picks out the grants held by a grantee.
   *
   * <p>{@code PRINCIPAL} means "of unknown kind". A statement that does not
   * say what kind of principal it means matches the name whatever its kind;
   * a statement that does say also matches grants whose kind is unknown,
   * as reported by backends that cannot tell users from roles. */
  private static String grantee(SqlGrant.Grantee g) {
    final String name = SqlText.eq("GRANTEE", g.name());
    if (g.type().equals(Grant.PRINCIPAL)) {
      return name;
    }
    return SqlText.id("GRANTEE_TYPE") + " IN (" + SqlText.str(g.type())
        + ", " + SqlText.str(Grant.PRINCIPAL) + ") AND " + name;
  }

  private static String grantees(List<SqlGrant.Grantee> grantees) {
    if (grantees.size() == 1) {
      return grantee(grantees.get(0));
    }
    final StringJoiner j = new StringJoiner(" OR ", "(", ")");
    grantees.forEach(g -> j.add("(" + grantee(g) + ")"));
    return j.toString();
  }

  // -- PAUSE, RESUME, FIRE, REFRESH -----------------------------------------

  private Lowered control(SqlControl n) {
    final Name name = object(n.name);
    switch (n.verb) {
    case PAUSE:
    case RESUME:
      final String paused =
          SqlText.id("PAUSED") + " = "
              + SqlText.bool(n.verb == SqlControl.Verb.PAUSE);
      final Lowered pause =
          targeted(name.catalog, home(n.kind).table,
              update(home(n.kind).table, paused, where(n.kind, name)),
              notFound(n.kind, name));
      if (n.kind != ObjectKind.MATERIALIZED_VIEW || !scheduled(name)) {
        return pause;
      }
      // And its schedule with it.
      final List<Step> both = new ArrayList<>(pause.steps());
      both.add(
          new Step(update("TRIGGERS", paused, triggerOf(name)), false, null));
      return new Lowered(both);
    case FIRE:
      // A job, for whatever runs the catalog's jobs: the trigger's action,
      // under the trigger's name.
      final String action =
          find(name, Catalog::triggers).map(TriggerInfo::action)
              .orElseThrow(() ->
                  new NotFoundException(notFound(n.kind, name)));
      return Lowered.of(
          insert(false, "JOBS",
              List.of(
                  row(CATALOG_NAME, SqlText.str(name.catalog),
                      "SCHEMA_NAME", SqlText.str(name.schema),
                      "JOB_NAME", SqlText.str(name.name),
                      "SUBMITTED_AT", "CURRENT_TIMESTAMP",
                          "SUBMITTED_BY", "CURRENT_USER",
                      "STATEMENT", SqlText.str(action),
                      "OPTIONS", SqlText.map(n.options)))));
    case REFRESH:
      // Out with the old rows and in with the new.
      final MaterializedViewInfo view =
          find(name, Catalog::materializedViews)
              .orElseThrow(() ->
                  new NotFoundException(notFound(n.kind, name)));
      if (view.continuous()) {
        throw new UnsupportedException("Materialized view " + name
            + " is refreshed continuously, by a job; there is nothing to"
            + " refresh by hand");
      }
      return new Lowered(
          List.of(empty(name), fill(name, view.definition())));
    default:
      throw new AssertionError(n.verb);
    }
  }

  /** Finds the row of an object, if there is one. */
  private <R extends MetaRow> Optional<R> find(Name name,
      Function<Catalog, MetaTable<R>> table) {
    final Catalog catalog = context.catalog(name.catalog);
    return catalog == null ? Optional.empty()
        : table.apply(catalog).get(Key.of(name.schema, name.name));
  }

  // -- ALTER TABLE ----------------------------------------------------------

  private Lowered alterTable(SqlAlterTable n) {
    final Name name = object(n.name);
    final String where = where(ObjectKind.TABLE, name);
    final String missing = notFound(ObjectKind.TABLE, name);
    switch (n.action) {
    case ADD_COLUMN:
      return Lowered.of(
          insert(false, "COLUMNS",
              List.of(
                  row(CATALOG_NAME, SqlText.str(name.catalog),
                      "SCHEMA_NAME", SqlText.str(name.schema),
                      "TABLE_NAME", SqlText.str(name.name),
                      "COLUMN_NAME",
                      SqlText.str(n.column.name().getSimple()),
                      "DATA_TYPE", SqlText.str(type(n.column)),
                      "NULLABLE", SqlText.bool(n.column.nullable()),
                      "COMMENT", SqlText.str(n.column.comment())))));
    case DROP_COLUMN:
      return targeted(name.catalog, "COLUMNS",
          delete("COLUMNS",
              where + " AND "
                  + SqlText.eq("COLUMN_NAME", n.target.getSimple())),
          "Column " + name + "." + n.target.getSimple() + " not found");
    case RENAME:
      final Name target =
          n.target.isSimple()
              ? new Name(name.catalog, name.schema, n.target.getSimple())
              : object(n.target);
      if (!target.catalog.equals(name.catalog)) {
        throw new CatalogException("Cannot rename table " + name
            + " into another catalog");
      }
      return targeted(name.catalog, "TABLES",
          update("TABLES",
              SqlText.eq("SCHEMA_NAME", target.schema) + ", "
                  + SqlText.eq("TABLE_NAME", target.name),
              where),
          missing);
    case SET_OPTIONS:
      // SET merges into the existing options, so it needs to read them.
      final Map<String, String> options = new LinkedHashMap<>();
      final Catalog catalog = context.catalog(name.catalog);
      if (catalog != null) {
        try {
          catalog.tables().get(Key.of(name.schema, name.name))
              .map(TableInfo::options).ifPresent(options::putAll);
        } catch (UnsupportedException e) {
          // The update below will report it.
        }
      }
      options.putAll(n.options);
      return targeted(name.catalog, "TABLES",
          update("TABLES",
              SqlText.id("OPTIONS") + " = " + SqlText.map(options), where),
          missing);
    case ADD_PARTITION:
      return new Lowered(
          List.of(
              new Step(
                  insert(false, "PARTITIONS",
                      List.of(
                          row(CATALOG_NAME, SqlText.str(name.catalog),
                              "SCHEMA_NAME", SqlText.str(name.schema),
                              "TABLE_NAME", SqlText.str(name.name),
                              "SPEC", SqlText.map(n.partition),
                              "OPTIONS", SqlText.map(n.options)))),
                  n.lenient, null)));
    case DROP_PARTITION:
      final StringJoiner spec = new StringJoiner(" AND ");
      spec.add(where);
      spec.add("CARDINALITY(" + SqlText.id("SPEC") + ") = "
          + n.partition.size());
      n.partition.forEach((k, v) ->
          spec.add(SqlText.id("SPEC") + "[" + SqlText.str(k) + "] = "
              + SqlText.str(v)));
      return targeted(name.catalog, "PARTITIONS",
          delete("PARTITIONS", spec.toString()),
          n.lenient ? null
              : "Partition " + n.partition + " of " + name + " not found");
    default:
      throw new AssertionError(n.action);
    }
  }

  // -- USE, SET -------------------------------------------------------------

  private static Step set(String setting, String value) {
    return new Step(
        update("SETTINGS",
            SqlText.id("SETTING_VALUE") + " = "
                + (value == null ? "CAST(NULL AS VARCHAR)"
                    : SqlText.str(value)),
            SqlText.eq("SETTING_NAME", setting)),
        false, "Unknown setting '" + setting + "'");
  }

  private Lowered use(SqlUse n) {
    final List<String> names = n.name.names;
    if (n.kind == ObjectKind.CATALOG) {
      return new Lowered(List.of(set(Setting.CATALOG, names.get(0))));
    }
    if (names.size() == 2) {
      return new Lowered(
          List.of(set(Setting.CATALOG, names.get(0)),
              set(Setting.SCHEMA, names.get(1))));
    } else if (names.size() > 2) {
      throw tooManyParts(n.name, 2);
    }
    final String name = names.get(0);
    if (n.kind == null && !schemaExists(context.catalog(), name)
        && context.catalog(name) != null) {
      // A bare name that is not a schema here, but is a catalog.
      return new Lowered(List.of(set(Setting.CATALOG, name)));
    }
    return new Lowered(List.of(set(Setting.SCHEMA, name)));
  }

  private boolean schemaExists(String catalogName, String schemaName) {
    final Catalog catalog =
        catalogName == null ? null : context.catalog(catalogName);
    if (catalog == null) {
      return false;
    }
    try {
      return catalog.schemas().get(Key.of(schemaName)).isPresent();
    } catch (UnsupportedException e) {
      return false;
    }
  }

  private Lowered setOption(SqlSetOption n) {
    if (n.getScope() != null) {
      throw new CatalogException("ALTER " + n.getScope()
          + " is not supported; use SET");
    }
    final String setting = n.name().toString().toLowerCase(Locale.ROOT);
    final SqlNode value = n.getValue();
    final String text;
    if (value == null) {
      text = null; // RESET
    } else if (value instanceof SqlLiteral literal) {
      text = io.monoptic.monoql.sql.SqlClauses.text(literal);
    } else {
      text = value.toString();
    }
    return new Lowered(List.of(set(setting, text)));
  }

  // -- SHOW, DESCRIBE -------------------------------------------------------

  private static String select(String columns, String table, String where,
      String orderBy) {
    return "SELECT " + columns + "\nFROM " + M + SqlText.id(table)
        + (where.isEmpty() ? "" : "\nWHERE " + where)
        + (orderBy == null ? "" : "\nORDER BY " + orderBy);
  }

  private static String cols(String... names) {
    final StringJoiner j = new StringJoiner(", ");
    for (String name : names) {
      j.add(SqlText.id(name));
    }
    return j.toString();
  }

  private Lowered show(SqlShow n) {
    final StringJoiner where = new StringJoiner(" AND ");
    final String sql;
    // The catalog the statement looks in, if it looks in just one.
    String catalog = null;
    switch (n.what) {
    case CATALOGS:
      like(where, CATALOG_NAME, n.like);
      sql = select(cols(CATALOG_NAME, "TYPE", "COMMENT"), "CATALOGS",
          where.toString(), "1");
      break;
    case SETTINGS:
      like(where, "SETTING_NAME", n.like);
      sql = select(cols("SETTING_NAME", "SETTING_VALUE"), "SETTINGS",
          where.toString(), "1");
      break;
    case SCHEMAS:
      catalog = n.scope == null ? currentCatalog() : catalog(n.scope).catalog;
      where.add(SqlText.eq(CATALOG_NAME, catalog));
      like(where, "SCHEMA_NAME", n.like);
      sql = select(cols("SCHEMA_NAME", "COMMENT"), "SCHEMAS",
          where.toString(), "1");
      break;
    case ROLES:
    case USERS:
      final String nameColumn =
          n.what == SqlShow.What.ROLES ? "ROLE_NAME" : "USER_NAME";
      catalog = n.scope == null ? currentCatalog() : catalog(n.scope).catalog;
      where.add(SqlText.eq(CATALOG_NAME, catalog));
      like(where, nameColumn, n.like);
      sql = select(cols(nameColumn, "COMMENT"), n.what.name(),
          where.toString(), "1");
      break;
    case TABLES:
      catalog = showSchema(n).catalog;
      sql = showInSchema(n, "TABLE_NAME", cols("TABLE_NAME", "COMMENT"));
      break;
    case VIEWS:
      catalog = showSchema(n).catalog;
      sql = showInSchema(n, "VIEW_NAME", cols("VIEW_NAME", "COMMENT"));
      break;
    case MATERIALIZED_VIEWS:
      catalog = showSchema(n).catalog;
      sql = showInSchema(n, "VIEW_NAME",
          cols("VIEW_NAME", "CONTINUOUS", "PAUSED", "COMMENT"));
      break;
    case FUNCTIONS:
      catalog = showSchema(n).catalog;
      sql = showInSchema(n, "FUNCTION_NAME",
          cols("FUNCTION_NAME", "FUNCTION_LANGUAGE", "COMMENT"));
      break;
    case TRIGGERS:
      catalog = showSchema(n).catalog;
      sql = showInSchema(n, "TRIGGER_NAME",
          cols("TRIGGER_NAME", "TABLE_NAME", "SCHEDULE", "PAUSED",
              "COMMENT"));
      break;
    case COLUMNS:
      catalog = object(n.scope).catalog;
      where.add(where(ObjectKind.TABLE, object(n.scope)));
      like(where, "COLUMN_NAME", n.like);
      sql = select(cols("COLUMN_NAME", "DATA_TYPE", "NULLABLE", "COMMENT"),
          "COLUMNS", where.toString(), SqlText.id("ORDINAL_POSITION"));
      break;
    case PARTITIONS:
      catalog = object(n.scope).catalog;
      where.add(where(ObjectKind.TABLE, object(n.scope)));
      // Maps have no order; partitions come back in catalog order.
      sql = select(cols("SPEC", "OPTIONS"), "PARTITIONS", where.toString(),
          null);
      break;
    case GRANTS:
      if (n.scope != null) {
        final Name object = resolve(n.objectKind, n.scope);
        catalog = object.catalog;
        where.add(SqlText.eq(CATALOG_NAME, object.catalog));
        where.add(SqlText.eq("OBJECT_TYPE", n.objectKind.sql));
        where.add(SqlText.eq("OBJECT_SCHEMA", object.schema));
        where.add(SqlText.eq("OBJECT_NAME", object.name));
      } else {
        catalog = currentCatalog();
        where.add(SqlText.eq(CATALOG_NAME, catalog));
      }
      if (n.grantee != null) {
        where.add(grantee(n.grantee));
      }
      sql = select(
          cols("PRIVILEGE", "OBJECT_TYPE", "OBJECT_SCHEMA", "OBJECT_NAME",
              "GRANTEE_TYPE", "GRANTEE", "GRANTABLE"),
          "GRANTS", where.toString(), "2, 3, 4, 5, 6, 1");
      break;
    default:
      throw new AssertionError(n.what);
    }
    // An empty answer is fine; but if the catalog cannot say, say so.
    return catalog == null ? Lowered.of(sql)
        : targeted(catalog, table(n.what), sql, null);
  }

  private static String table(SqlShow.What what) {
    return what.name();
  }

  private Name showSchema(SqlShow n) {
    return n.scope == null
        ? new Name(currentCatalog(), currentSchema(), null)
        : schema(n.scope);
  }

  private String showInSchema(SqlShow n, String nameColumn, String columns) {
    final Name schema = showSchema(n);
    final StringJoiner where = new StringJoiner(" AND ");
    where.add(SqlText.eq(CATALOG_NAME, schema.catalog));
    where.add(SqlText.eq("SCHEMA_NAME", schema.schema));
    like(where, nameColumn, n.like);
    return select(columns, n.what.name(), where.toString(), "1");
  }

  private static void like(StringJoiner where, String column, String like) {
    if (like != null) {
      where.add(SqlText.id(column) + " LIKE " + SqlText.str(like));
    }
  }

  private Lowered describeTable(SqlDescribeTable n) {
    final Name table = object(n.getTable());
    final StringJoiner where = new StringJoiner(" AND ");
    where.add(where(ObjectKind.TABLE, table));
    if (n.getColumn() != null) {
      where.add(SqlText.eq("COLUMN_NAME", n.getColumn().getSimple()));
    }
    return targeted(table.catalog, "COLUMNS",
        select(cols("COLUMN_NAME", "DATA_TYPE", "NULLABLE", "COMMENT"),
            "COLUMNS", where.toString(), SqlText.id("ORDINAL_POSITION")),
        // A view has columns only while its definition is valid.
        "Table or view " + table + " not found, or it is a view whose"
            + " definition is no longer valid");
  }

  private Lowered describeSchema(SqlDescribeSchema n) {
    final Name name = schema(n.getSchema());
    return targeted(name.catalog, "SCHEMAS",
        select(cols(CATALOG_NAME, "SCHEMA_NAME", "COMMENT", "OPTIONS"),
            "SCHEMAS", where(ObjectKind.SCHEMA, name), "1"),
        notFound(ObjectKind.SCHEMA, name));
  }

}
