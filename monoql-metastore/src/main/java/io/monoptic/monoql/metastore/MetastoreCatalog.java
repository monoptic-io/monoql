/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.metastore;

import io.monoptic.monoql.catalog.AlreadyExistsException;
import io.monoptic.monoql.catalog.Catalog;
import io.monoptic.monoql.catalog.CatalogException;
import io.monoptic.monoql.catalog.Column;
import io.monoptic.monoql.catalog.MaterializedViewInfo;
import io.monoptic.monoql.catalog.SchemaInfo;
import io.monoptic.monoql.catalog.TableInfo;
import io.monoptic.monoql.catalog.standard.StandardCatalog;

import org.apache.calcite.schema.Table;
import org.apache.commons.dbcp2.BasicDataSource;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A catalog whose metadata is durable: every metadata table, with the
 * rules of {@link StandardCatalog}, kept in a SQL database.
 *
 * <p>One database serves many tenants, each with catalogs of its own; a
 * catalog sees the metadata of its tenant and name, and no other.
 *
 * <p>The metadata says what tables there are. Their rows are somewhere
 * else: in a <em>data catalog</em>, of any type, which is told to create,
 * alter and drop each table as this catalog is, and asked for its rows.
 * It holds the rows of materialized views in the same way. Without one,
 * tables are metadata only, and materialized views read as their
 * definitions.
 *
 * <p>A statement's changes to the metadata happen entirely or not at all
 * ({@link #atomically}). The data catalog is not part of that: its changes
 * are made last, so that a failure there undoes the metadata; but if the
 * metadata then cannot be committed, what the data catalog did stays
 * done. */
public final class MetastoreCatalog extends StandardCatalog {
  private final BasicDataSource dataSource;
  private final Catalog data;

  /** Creates a catalog.
   *
   * @param dataSource The metastore; closed with the catalog
   * @param tenant Whose metadata this is
   * @param name The catalog's name, which is part of what its metadata is
   *     stored under: a tenant may have several catalogs
   * @param create Whether to create the metastore's tables if they are
   *     not there
   * @param data Where the rows of tables are; closed with the catalog.
   *     Null if nowhere */
  MetastoreCatalog(BasicDataSource dataSource, String tenant, String name,
      boolean create, Catalog data) {
    super(new SqlStorage(dataSource, tenant, name, create));
    this.dataSource = dataSource;
    this.data = data;
  }

  /** What the data catalog needs to know of a table: its shape. Comments
   * and options are metadata, and stay here. */
  private static TableInfo shape(TableInfo table) {
    final List<Column> columns =
        table.columns().stream()
            .map(c ->
                new Column(c.columnName(), c.dataType(), c.nullable(), null))
            .toList();
    return new TableInfo(table.schemaName(), table.tableName(), columns,
        table.primaryKey(), table.partitionKeys(), null, Map.of());
  }

  /** Tables live in schemas, so the data catalog needs them too. One that
   * is already there will do. */
  @Override protected void schemaChanged(SchemaInfo before, SchemaInfo after) {
    if (data == null) {
      return;
    }
    if (before == null) {
      try {
        data.schemas().insert(
            new SchemaInfo(after.schemaName(), null, Map.of()));
      } catch (AlreadyExistsException e) {
        // Then there is somewhere to put its tables.
      }
    } else if (after == null) {
      data.schemas().delete(new SchemaInfo(before.schemaName(), null, Map.of()));
    }
  }

  @Override protected void tableChanged(TableInfo before, TableInfo after) {
    if (data == null) {
      return;
    }
    if (before == null) {
      data.tables().insert(shape(after));
    } else if (after == null) {
      data.tables().delete(shape(before));
    } else if (!shape(before).equals(shape(after))) {
      data.tables().update(shape(before), shape(after));
    }
  }

  @Override public Optional<Table> bind(TableInfo table) {
    return data == null ? Optional.empty() : data.bind(table);
  }

  /** The table in the data catalog that holds a materialized view's rows:
   * of the view's name, in the view's schema. */
  private static TableInfo storage(MaterializedViewInfo view,
      List<Column> columns) {
    return shape(
        new TableInfo(view.schemaName(), view.viewName(), columns, List.of(),
            List.of(), null, Map.of()));
  }

  /** The rows of a materialized view are in the data catalog too, in a
   * table made the first time they are wanted, which is as the view is
   * created. It keeps the shape it was made with; see
   * {@link #materializedViewChanged}. */
  @Override public Optional<Table> bind(MaterializedViewInfo view,
      List<Column> columns) {
    if (data == null) {
      return Optional.empty();
    }
    final TableInfo wanted = storage(view, columns);
    final Optional<TableInfo> existing = data.tables().get(wanted.key());
    if (existing.isEmpty()) {
      data.tables().insert(wanted);
    }
    return data.bind(existing.orElse(wanted));
  }

  /** The table that holds a view's rows goes when the view does, or when
   * it is given another query, since the rows are that query's no longer;
   * and follows a rename. */
  @Override protected void materializedViewChanged(
      MaterializedViewInfo before, MaterializedViewInfo after) {
    if (data == null || before == null) {
      return;
    }
    final Optional<TableInfo> existing = data.tables().get(before.key());
    if (existing.isEmpty()) {
      return;
    }
    final TableInfo table = existing.get();
    if (after == null
        || !after.definition().equals(before.definition())
        || !after.columnNames().equals(before.columnNames())) {
      data.tables().delete(table);
    } else if (!after.key().equals(before.key())) {
      data.tables().update(table,
          new TableInfo(after.schemaName(), after.viewName(), table.columns(),
              table.primaryKey(), table.partitionKeys(), table.comment(),
              table.options()));
    }
  }

  @Override public void close() {
    try {
      super.close();
      dataSource.close();
    } catch (SQLException e) {
      throw new CatalogException("Closing the metastore failed: "
          + e.getMessage(), e);
    } finally {
      if (data != null) {
        data.close();
      }
    }
  }
}
