/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.jdbc;

import io.monoptic.monoql.catalog.AlreadyExistsException;
import io.monoptic.monoql.catalog.Catalog;
import io.monoptic.monoql.catalog.CatalogException;
import io.monoptic.monoql.catalog.Column;
import io.monoptic.monoql.catalog.DataTypes;
import io.monoptic.monoql.catalog.Grant;
import io.monoptic.monoql.catalog.Key;
import io.monoptic.monoql.catalog.MetaTable;
import io.monoptic.monoql.catalog.MetaTables;
import io.monoptic.monoql.catalog.NotFoundException;
import io.monoptic.monoql.catalog.RoleInfo;
import io.monoptic.monoql.catalog.SchemaInfo;
import io.monoptic.monoql.catalog.TableInfo;
import io.monoptic.monoql.catalog.UnsupportedException;

import org.apache.calcite.adapter.jdbc.JdbcConvention;
import org.apache.calcite.adapter.jdbc.JdbcSchema;
import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.linq4j.tree.Expressions;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeSystem;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.lookup.LikePattern;
import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.SqlDialectFactoryImpl;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.type.SqlTypeFactoryImpl;

import org.apache.commons.dbcp2.BasicDataSource;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Logger;

import javax.sql.DataSource;

/** A catalog over a JDBC database.
 *
 * <p>What it supports is what JDBC and plain SQL can express portably:
 *
 * <table>
 * <caption>Support by metadata table</caption>
 * <tr><th>Table</th><th>Read</th><th>Write</th></tr>
 * <tr><td>SCHEMAS</td><td>yes</td><td>insert, delete</td></tr>
 * <tr><td>TABLES</td><td>yes</td><td>insert, delete; update only to rename,
 *     or to add or drop one column</td></tr>
 * <tr><td>GRANTS</td><td>table privileges</td><td>insert, delete, on
 *     tables</td></tr>
 * <tr><td>ROLES</td><td>no</td><td>insert, delete</td></tr>
 * <tr><td>everything else</td><td>no</td><td>no</td></tr>
 * </table>
 *
 * <p>Writes are translated to DDL and DCL in the database's dialect and
 * executed there; this class keeps no state of its own.
 *
 * <p>Table data is read and written by Calcite's JDBC adapter, which pushes
 * as much of each query as it can down to the database. */
public class JdbcCatalog implements Catalog {
  /** Live schemas, so that generated code can find its way back to the
   * data source: see {@link #schema(String)}. */
  private static final Map<String, JdbcSchema> REGISTRY =
      new ConcurrentHashMap<>();

  /** Unique among live catalogs; and a legal name for a Calcite calling
   * convention, which each schema needs one of. */
  private final String id =
      "jdbc" + UUID.randomUUID().toString().replace("-", "");
  private final Map<String, String> keys = new ConcurrentHashMap<>();
  /** The pool of connections to the database. */
  private final DataSource pool;
  /** What everything here, and Calcite's JDBC adapter, asks for
   * connections: the pool, except inside a unit; see
   * {@link #atomically}. */
  private final DataSource dataSource;
  /** Whether the database can undo; if not, there are no units. */
  private final boolean transactional;
  /** The unit the calling thread is in, if any. */
  private final ThreadLocal<Unit> unit = new ThreadLocal<>();
  private final SqlDialect dialect;
  private final String databaseCatalog;
  private final RelDataTypeFactory typeFactory =
      new SqlTypeFactoryImpl(RelDataTypeSystem.DEFAULT);

  public JdbcCatalog(String url, String driver, String user, String password,
      String databaseCatalog) {
    this.pool = JdbcSchema.dataSource(url, driver, user, password);
    this.dataSource = new UnitDataSource();
    boolean transactional = false;
    try (Connection connection = pool.getConnection()) {
      this.databaseCatalog =
          databaseCatalog != null ? databaseCatalog : connection.getCatalog();
      transactional = connection.getMetaData().supportsTransactions();
    } catch (SQLException e) {
      close();
      throw new CatalogException("Cannot connect to " + url + ": "
          + e.getMessage(), e);
    }
    this.transactional = transactional;
    this.dialect =
        JdbcSchema.createDialect(SqlDialectFactoryImpl.INSTANCE, dataSource);
  }

  // -- Units ----------------------------------------------------------------

  /** Runs some work as a transaction in the database, if the database has
   * them: everything this catalog and Calcite's JDBC adapter do on the
   * calling thread until the work returns is done on one connection, and
   * committed when it returns, or rolled back if it throws.
   *
   * <p>How much that covers is the database's business. Many commit
   * whatever is in progress when they are given DDL, so that a statement
   * which creates a table and then fills it is two units there. */
  @Override public <T> T atomically(Supplier<T> work) {
    if (!transactional || unit.get() != null) {
      return work.get();
    }
    final Unit u = new Unit();
    unit.set(u);
    boolean done = false;
    try {
      final T result = work.get();
      done = true;
      return result;
    } finally {
      unit.remove();
      u.end(done);
    }
  }

  /** A transaction, once something has asked for a connection. */
  private final class Unit {
    private Connection connection;
    /** What is handed out: the connection, which closing does not
     * close. */
    private Connection shared;

    Connection connection() throws SQLException {
      if (connection == null) {
        final Connection c = pool.getConnection();
        try {
          c.setAutoCommit(false);
        } catch (SQLException e) {
          c.close();
          throw e;
        }
        connection = c;
        shared =
            (Connection) Proxy.newProxyInstance(
                JdbcCatalog.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("close")) {
                    return null;
                  }
                  try {
                    return method.invoke(c, args);
                  } catch (InvocationTargetException e) {
                    throw e.getCause();
                  }
                });
      }
      return shared;
    }

    void end(boolean commit) {
      if (connection == null) {
        return;
      }
      try (Connection c = connection) {
        if (commit) {
          c.commit();
        } else {
          c.rollback();
        }
        c.setAutoCommit(true);
      } catch (SQLException e) {
        if (commit) {
          throw new CatalogException("The database did not commit: "
              + e.getMessage(), e);
        }
        // Rolling back is best effort; the database undoes what was not
        // committed when the connection goes.
      }
    }
  }

  /** The pool; except that inside a unit, every request for a connection
   * is given the unit's. */
  private final class UnitDataSource implements DataSource {
    @Override public Connection getConnection() throws SQLException {
      final Unit u = unit.get();
      return u == null ? pool.getConnection() : u.connection();
    }

    @Override public Connection getConnection(String username,
        String password) throws SQLException {
      return getConnection();
    }

    @Override public PrintWriter getLogWriter() throws SQLException {
      return pool.getLogWriter();
    }

    @Override public void setLogWriter(PrintWriter out) throws SQLException {
      pool.setLogWriter(out);
    }

    @Override public void setLoginTimeout(int seconds) throws SQLException {
      pool.setLoginTimeout(seconds);
    }

    @Override public int getLoginTimeout() throws SQLException {
      return pool.getLoginTimeout();
    }

    @Override public Logger getParentLogger()
        throws SQLFeatureNotSupportedException {
      return pool.getParentLogger();
    }

    @Override public <T> T unwrap(Class<T> iface) throws SQLException {
      return pool.unwrap(iface);
    }

    @Override public boolean isWrapperFor(Class<?> iface)
        throws SQLException {
      return pool.isWrapperFor(iface);
    }
  }

  /** Called by generated code to reach the data source of a schema. Not for
   * anyone else. */
  public static JdbcSchema schema(String key) {
    final JdbcSchema schema = REGISTRY.get(key);
    if (schema == null) {
      throw new IllegalStateException("The catalog this plan was made for"
          + " has been closed");
    }
    return schema;
  }

  /** Returns Calcite's view of one database schema, creating it on first
   * use. */
  private JdbcSchema jdbcSchema(String schemaName) {
    final String key = key(schemaName);
    return REGISTRY.computeIfAbsent(key, k -> {
      final Expression expression =
          Expressions.call(JdbcCatalog.class, "schema",
              Expressions.constant(key));
      return new JdbcSchema(dataSource, dialect,
          JdbcConvention.of(dialect, expression, key), databaseCatalog,
          schemaName);
    });
  }

  /** Returns the registry key of a schema. Schema names can contain
   * anything, so the key is a number. */
  private String key(String schemaName) {
    return keys.computeIfAbsent(schemaName, s -> id + "_" + keys.size());
  }

  /** Forgets cached metadata of a schema, after DDL. */
  private void invalidate(String schemaName) {
    REGISTRY.remove(key(schemaName));
  }

  @Override public void close() {
    REGISTRY.keySet().removeIf(key -> key.startsWith(id + "_"));
    if (pool instanceof BasicDataSource basic) {
      try {
        basic.close();
      } catch (SQLException e) {
        // nothing useful to do
      }
    }
  }

  // -- JDBC helpers ---------------------------------------------------------

  /** Something to do with database metadata. */
  private interface MetaFunction<T> {
    T apply(DatabaseMetaData metaData) throws SQLException;
  }

  private <T> T metadata(MetaFunction<T> function) {
    try (Connection connection = dataSource.getConnection()) {
      return function.apply(connection.getMetaData());
    } catch (SQLException e) {
      throw new CatalogException(e.getMessage(), e);
    }
  }

  private void execute(String sql) {
    try (Connection connection = dataSource.getConnection();
         Statement statement = connection.createStatement()) {
      statement.execute(sql);
    } catch (SQLException e) {
      throw new CatalogException(e.getMessage() + " [" + sql + "]", e);
    }
  }

  private String quote(String... names) {
    final StringJoiner j = new StringJoiner(".");
    for (String name : names) {
      j.add(dialect.quoteIdentifier(name));
    }
    return j.toString();
  }

  private boolean schemaExists(String schemaName) {
    for (SchemaInfo schema : schemas.scan()) {
      if (schema.schemaName().equals(schemaName)) {
        return true;
      }
    }
    return false;
  }

  /** Renders a column type in the database's dialect. */
  private String type(Column column) {
    final RelDataType type =
        DataTypes.parse(column.dataType(), column.nullable(), typeFactory);
    final SqlNode spec = dialect.getCastSpec(type);
    final String sql = spec == null ? column.dataType()
        : spec.toSqlString(dialect).getSql();
    return column.nullable() ? sql : sql + " NOT NULL";
  }

  private static void requireEmpty(Map<String, String> options, String what) {
    if (!options.isEmpty()) {
      throw new UnsupportedException("A jdbc catalog does not support "
          + what + " options: " + options.keySet());
    }
  }

  // -- SCHEMAS --------------------------------------------------------------

  private final MetaTable<SchemaInfo> schemas = new MetaTable<>() {
    @Override public String name() {
      return MetaTables.SCHEMAS.name();
    }

    @Override public Iterable<SchemaInfo> scan() {
      return metadata(metaData -> {
        final List<SchemaInfo> rows = new ArrayList<>();
        try (ResultSet r = metaData.getSchemas(databaseCatalog, null)) {
          while (r.next()) {
            rows.add(new SchemaInfo(r.getString("TABLE_SCHEM"), null, null));
          }
        }
        return rows;
      });
    }

    @Override public void insert(SchemaInfo row) {
      requireEmpty(row.options(), "schema");
      if (row.comment() != null) {
        throw new UnsupportedException(
            "A jdbc catalog does not support schema comments");
      }
      if (schemaExists(row.schemaName())) {
        throw AlreadyExistsException.of(name(), row.key());
      }
      execute("CREATE SCHEMA " + quote(row.schemaName()));
    }

    @Override public void delete(SchemaInfo row) {
      if (!schemaExists(row.schemaName())) {
        throw NotFoundException.of(name(), row.key());
      }
      execute("DROP SCHEMA " + quote(row.schemaName()));
      invalidate(row.schemaName());
    }
  };

  // -- TABLES ---------------------------------------------------------------

  private TableInfo tableInfo(String schemaName, String tableName,
      Table table) {
    final List<String> primaryKey = metadata(metaData -> {
      final Map<Integer, String> keys = new TreeMap<>();
      try (ResultSet r =
               metaData.getPrimaryKeys(databaseCatalog, schemaName,
                   tableName)) {
        while (r.next()) {
          keys.put(r.getInt("KEY_SEQ"), r.getString("COLUMN_NAME"));
        }
      }
      return new ArrayList<>(keys.values());
    });
    return new TableInfo(schemaName, tableName,
        DataTypes.columns(table.getRowType(typeFactory)), primaryKey, null,
        null, null);
  }

  private final MetaTable<TableInfo> tables = new MetaTable<>() {
    @Override public String name() {
      return MetaTables.TABLES.name();
    }

    @Override public Iterable<TableInfo> scan() {
      final List<TableInfo> rows = new ArrayList<>();
      for (SchemaInfo schema : schemas.scan()) {
        final JdbcSchema jdbcSchema = jdbcSchema(schema.schemaName());
        for (String tableName
            : jdbcSchema.tables().getNames(LikePattern.any())) {
          final Table table = jdbcSchema.tables().get(tableName);
          if (table != null
              && table.getJdbcTableType()
                  == org.apache.calcite.schema.Schema.TableType.TABLE) {
            rows.add(tableInfo(schema.schemaName(), tableName, table));
          }
        }
      }
      return rows;
    }

    @Override public Optional<TableInfo> get(Key key) {
      final String schemaName = (String) key.parts().get(0);
      final String tableName = (String) key.parts().get(1);
      final Table table = jdbcSchema(schemaName).tables().get(tableName);
      return table == null ? Optional.empty()
          : Optional.of(tableInfo(schemaName, tableName, table));
    }

    @Override public void insert(TableInfo row) {
      requireEmpty(row.options(), "table");
      if (row.comment() != null || !row.partitionKeys().isEmpty()
          || row.columns().stream().anyMatch(c -> c.comment() != null)) {
        throw new UnsupportedException("A jdbc catalog does not support"
            + " comments or partitioning on tables");
      }
      if (get(row.key()).isPresent()) {
        throw AlreadyExistsException.of(name(), row.key());
      }
      final StringJoiner columns = new StringJoiner(", ", " (", ")");
      for (Column column : row.columns()) {
        columns.add(quote(column.columnName()) + " " + type(column));
      }
      if (!row.primaryKey().isEmpty()) {
        final StringJoiner key = new StringJoiner(", ", "PRIMARY KEY (", ")");
        row.primaryKey().forEach(k -> key.add(quote(k)));
        columns.add(key.toString());
      }
      execute("CREATE TABLE " + quote(row.schemaName(), row.tableName())
          + columns);
      invalidate(row.schemaName());
    }

    @Override public void update(TableInfo before, TableInfo after) {
      final String table = quote(before.schemaName(), before.tableName());
      final List<Column> was = before.columns();
      final List<Column> now = after.columns();
      final boolean sameRest = before.primaryKey().equals(after.primaryKey())
          && before.options().equals(after.options())
          && java.util.Objects.equals(before.comment(), after.comment())
          && before.partitionKeys().equals(after.partitionKeys());
      if (!sameRest) {
        throw new UnsupportedException("A jdbc catalog can rename a table,"
            + " and add or drop a column; it cannot change keys, comments"
            + " or options");
      }
      if (!before.key().equals(after.key())) {
        if (!was.equals(now)
            || !before.schemaName().equals(after.schemaName())) {
          throw new UnsupportedException("A jdbc catalog can rename a table"
              + " only within its schema, and only on its own");
        }
        execute("ALTER TABLE " + table + " RENAME TO "
            + quote(after.tableName()));
      } else if (now.size() == was.size() + 1
          && now.subList(0, was.size()).equals(was)) {
        final Column column = now.get(was.size());
        execute("ALTER TABLE " + table + " ADD COLUMN "
            + quote(column.columnName()) + " " + type(column));
      } else if (now.size() == was.size() - 1 && was.containsAll(now)) {
        final List<Column> dropped = new ArrayList<>(was);
        dropped.removeAll(now);
        execute("ALTER TABLE " + table + " DROP COLUMN "
            + quote(dropped.get(0).columnName()));
      } else if (!was.equals(now)) {
        throw new UnsupportedException("A jdbc catalog can add a column at"
            + " the end of a table, or drop one, one at a time");
      }
      invalidate(before.schemaName());
    }

    @Override public void delete(TableInfo row) {
      if (get(row.key()).isEmpty()) {
        throw NotFoundException.of(name(), row.key());
      }
      execute("DROP TABLE " + quote(row.schemaName(), row.tableName()));
      invalidate(row.schemaName());
    }
  };

  // -- GRANTS, ROLES --------------------------------------------------------

  private static void requireTable(Grant grant) {
    if (!grant.objectType().equals("TABLE") || grant.objectName() == null) {
      throw new UnsupportedException("A jdbc catalog supports grants on"
          + " tables only, not on " + grant.objectType());
    }
  }

  private final MetaTable<Grant> grants = new MetaTable<>() {
    @Override public String name() {
      return MetaTables.GRANTS.name();
    }

    /** JDBC does not say what kind of principal a grantee is, so every
     * grant is reported as held by a {@code PRINCIPAL}. */
    @Override public Iterable<Grant> scan() {
      return metadata(metaData -> {
        final List<Grant> rows = new ArrayList<>();
        try (ResultSet r =
                 metaData.getTablePrivileges(databaseCatalog, null, "%")) {
          while (r.next()) {
            rows.add(
                new Grant(r.getString("PRIVILEGE"), "TABLE",
                    r.getString("TABLE_SCHEM"), r.getString("TABLE_NAME"),
                    Grant.PRINCIPAL, r.getString("GRANTEE"),
                    "YES".equalsIgnoreCase(r.getString("IS_GRANTABLE"))));
          }
        }
        return rows;
      });
    }

    @Override public void insert(Grant row) {
      requireTable(row);
      execute("GRANT " + row.privilege() + " ON TABLE "
          + quote(row.objectSchema(), row.objectName()) + " TO "
          + quote(row.grantee())
          + (row.grantable() ? " WITH GRANT OPTION" : ""));
    }

    @Override public void delete(Grant row) {
      requireTable(row);
      execute("REVOKE " + row.privilege() + " ON TABLE "
          + quote(row.objectSchema(), row.objectName()) + " FROM "
          + quote(row.grantee()) + " RESTRICT");
    }
  };

  private final MetaTable<RoleInfo> roles = new MetaTable<>() {
    @Override public String name() {
      return MetaTables.ROLES.name();
    }

    @Override public void insert(RoleInfo row) {
      requireEmpty(row.options(), "role");
      execute("CREATE ROLE " + quote(row.roleName()));
    }

    @Override public void delete(RoleInfo row) {
      execute("DROP ROLE " + quote(row.roleName()));
    }
  };

  // -- Catalog --------------------------------------------------------------

  @Override public MetaTable<SchemaInfo> schemas() {
    return schemas;
  }

  @Override public MetaTable<TableInfo> tables() {
    return tables;
  }

  @Override public MetaTable<Grant> grants() {
    return grants;
  }

  @Override public MetaTable<RoleInfo> roles() {
    return roles;
  }

  @Override public Optional<Table> bind(TableInfo table) {
    return Optional.ofNullable(
        jdbcSchema(table.schemaName()).tables().get(table.tableName()));
  }
}
