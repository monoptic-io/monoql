/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.metastore;

import io.monoptic.monoql.catalog.CatalogException;
import io.monoptic.monoql.catalog.Key;
import io.monoptic.monoql.catalog.MetaRow;
import io.monoptic.monoql.catalog.MetaTables;
import io.monoptic.monoql.catalog.NotFoundException;
import io.monoptic.monoql.catalog.standard.RowStore;
import io.monoptic.monoql.catalog.standard.Storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import javax.sql.DataSource;

/** Metadata kept in a SQL database, for many tenants and many catalogs at
 * once.
 *
 * <p>There are two tables. {@code monoql_metadata} has a row for each row
 * of each metadata table: whose it is (tenant, catalog, table), its key,
 * and the row itself, as JSON. {@code monoql_tenants} has a row for each
 * tenant, which is what writers lock: one writer per tenant at a time.
 * Deleting a tenant's row deletes its metadata.
 *
 * <p>A unit ({@link #atomically}) is a database transaction, begun at the
 * unit's first change, by taking the tenant's lock; until then, and
 * outside units, reads see what has been committed. A unit is confined to
 * its thread. */
final class SqlStorage implements Storage {
  static final List<String> DDL =
      List.of(
          "CREATE TABLE IF NOT EXISTS monoql_tenants ("
              + "tenant_id VARCHAR(255) NOT NULL PRIMARY KEY, "
              + "version BIGINT NOT NULL)",
          "CREATE TABLE IF NOT EXISTS monoql_metadata ("
              + "tenant_id VARCHAR(255) NOT NULL REFERENCES monoql_tenants"
              + " ON DELETE CASCADE, "
              + "catalog_name VARCHAR(255) NOT NULL, "
              + "table_name VARCHAR(64) NOT NULL, "
              + "row_key VARCHAR(2000) NOT NULL, "
              + "row_data TEXT NOT NULL, "
              + "PRIMARY KEY (tenant_id, catalog_name, table_name, row_key))");

  /** Rows as JSON: the components of the record, with maps in key order
   * and times in ISO 8601, so that equal rows are equal text. */
  private static final ObjectMapper JSON =
      JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .build();

  private final DataSource dataSource;
  private final String tenant;
  private final String catalog;
  private final ThreadLocal<Unit> unit = new ThreadLocal<>();

  /** Creates storage for one catalog of one tenant.
   *
   * @param create Whether to create the two tables if they are not there */
  SqlStorage(DataSource dataSource, String tenant, String catalog,
      boolean create) {
    this.dataSource = dataSource;
    this.tenant = tenant;
    this.catalog = catalog;
    try (Connection connection = dataSource.getConnection()) {
      if (create) {
        for (String ddl : DDL) {
          try (Statement statement = connection.createStatement()) {
            statement.execute(ddl);
          } catch (SQLException e) {
            // Two servers starting at once can both find the table
            // missing, and one of them then fails to create it. Whether
            // the tables are there is what matters, and is checked next.
          }
        }
      }
      try (Statement statement = connection.createStatement()) {
        statement.execute("SELECT tenant_id, version FROM monoql_tenants"
            + " WHERE 1 = 0");
        statement.execute("SELECT tenant_id, catalog_name, table_name,"
            + " row_key, row_data FROM monoql_metadata WHERE 1 = 0");
      } catch (SQLException e) {
        throw new CatalogException("The metastore does not have the tables"
            + " monoql_tenants and monoql_metadata"
            + (create ? ", and they could not be created" : "") + ": "
            + e.getMessage(), e);
      }
      try (PreparedStatement insert =
          connection.prepareStatement("INSERT INTO monoql_tenants"
              + " (tenant_id, version) VALUES (?, 0)")) {
        insert.setString(1, tenant);
        insert.executeUpdate();
      } catch (SQLException e) {
        if (!duplicate(e)) {
          throw e;
        }
      }
    } catch (SQLException e) {
      throw failed(e);
    }
  }

  private static boolean duplicate(SQLException e) {
    return e instanceof SQLIntegrityConstraintViolationException
        || e.getSQLState() != null && e.getSQLState().startsWith("23");
  }

  private static CatalogException failed(SQLException e) {
    return new CatalogException("The metastore failed: " + e.getMessage(), e);
  }

  // -- Units ----------------------------------------------------------------

  /** A transaction, once it has something to be about. */
  private final class Unit {
    Connection connection;

    /** Begins the transaction, if it has not begun, by taking the tenant's
     * lock: other writers wait here until this unit ends. */
    Connection lock() throws SQLException {
      if (connection == null) {
        final Connection c = dataSource.getConnection();
        try {
          c.setAutoCommit(false);
          try (PreparedStatement update =
              c.prepareStatement("UPDATE monoql_tenants"
                  + " SET version = version + 1 WHERE tenant_id = ?")) {
            update.setString(1, tenant);
            if (update.executeUpdate() == 0) {
              throw new NotFoundException("Tenant " + tenant
                  + " is no longer in the metastore");
            }
          }
        } catch (SQLException | RuntimeException e) {
          end(c, false);
          throw e;
        }
        connection = c;
      }
      return connection;
    }
  }

  private static void end(Connection connection, boolean commit) {
    try (Connection c = connection) {
      if (commit) {
        c.commit();
      } else {
        c.rollback();
      }
      c.setAutoCommit(true);
    } catch (SQLException e) {
      if (commit) {
        throw failed(e);
      }
      // Undoing is best effort; the database undoes what was not committed.
    }
  }

  @Override public <T> T atomically(Supplier<T> work) {
    if (unit.get() != null) {
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
      if (u.connection != null) {
        end(u.connection, done);
      }
    }
  }

  @Override public void write(Runnable work) {
    atomically(() -> {
      try {
        unit.get().lock();
      } catch (SQLException e) {
        throw failed(e);
      }
      work.run();
      return null;
    });
  }

  /** Runs a statement: in the unit's transaction if it has begun, and
   * otherwise on a connection of its own. */
  private <T> T run(String sql, SqlFunction<T> body) {
    final Unit u = unit.get();
    try {
      if (u != null && u.connection != null) {
        try (PreparedStatement statement = u.connection.prepareStatement(sql)) {
          return body.apply(statement);
        }
      }
      try (Connection connection = dataSource.getConnection();
          PreparedStatement statement = connection.prepareStatement(sql)) {
        return body.apply(statement);
      }
    } catch (SQLException e) {
      throw failed(e);
    }
  }

  /** What to do with a statement. */
  private interface SqlFunction<T> {
    T apply(PreparedStatement statement) throws SQLException;
  }

  // -- Rows -----------------------------------------------------------------

  @Override public <R extends MetaRow> RowStore<R> rows(
      MetaTables.Def<R> def) {
    return new Rows<>(def);
  }

  private static final String WHERE =
      " WHERE tenant_id = ? AND catalog_name = ? AND table_name = ?";

  /** The rows of one metadata table. */
  private final class Rows<R extends MetaRow> implements RowStore<R> {
    private final MetaTables.Def<R> def;

    Rows(MetaTables.Def<R> def) {
      this.def = def;
    }

    private int scope(PreparedStatement statement, int i) throws SQLException {
      statement.setString(i++, tenant);
      statement.setString(i++, catalog);
      statement.setString(i++, def.name());
      return i;
    }

    private String key(Key key) {
      try {
        return JSON.writeValueAsString(key.parts());
      } catch (JsonProcessingException e) {
        throw new CatalogException("Cannot store the key " + key, e);
      }
    }

    private R row(String json) {
      try {
        return JSON.readValue(json, def.rowType());
      } catch (JsonProcessingException e) {
        throw new CatalogException("The metastore has a row of " + def.name()
            + " that cannot be read: " + e.getOriginalMessage(), e);
      }
    }

    @Override public List<R> scan() {
      return run("SELECT row_data FROM monoql_metadata" + WHERE
          + " ORDER BY row_key", statement -> {
            scope(statement, 1);
            final List<R> rows = new ArrayList<>();
            try (ResultSet r = statement.executeQuery()) {
              while (r.next()) {
                rows.add(row(r.getString(1)));
              }
            }
            return rows;
          });
    }

    @Override public Optional<R> get(Key key) {
      return run("SELECT row_data FROM monoql_metadata" + WHERE
          + " AND row_key = ?", statement -> {
            statement.setString(scope(statement, 1), key(key));
            try (ResultSet r = statement.executeQuery()) {
              return r.next() ? Optional.of(row(r.getString(1)))
                  : Optional.<R>empty();
            }
          });
    }

    @Override public void put(R row) {
      final String json;
      try {
        json = JSON.writeValueAsString(row);
      } catch (JsonProcessingException e) {
        throw new CatalogException("Cannot store " + row, e);
      }
      final String key = key(row.key());
      final int updated =
          run("UPDATE monoql_metadata SET row_data = ?" + WHERE
              + " AND row_key = ?", statement -> {
                statement.setString(1, json);
                statement.setString(scope(statement, 2), key);
                return statement.executeUpdate();
              });
      if (updated == 0) {
        run("INSERT INTO monoql_metadata (tenant_id, catalog_name,"
            + " table_name, row_key, row_data) VALUES (?, ?, ?, ?, ?)",
            statement -> {
              final int i = scope(statement, 1);
              statement.setString(i, key);
              statement.setString(i + 1, json);
              return statement.executeUpdate();
            });
      }
    }

    @Override public void remove(Key key) {
      run("DELETE FROM monoql_metadata" + WHERE + " AND row_key = ?",
          statement -> {
            statement.setString(scope(statement, 1), key(key));
            return statement.executeUpdate();
          });
    }
  }
}
