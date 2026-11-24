/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.metastore;

import io.monoptic.monoql.catalog.AlreadyExistsException;
import io.monoptic.monoql.catalog.Catalog;
import io.monoptic.monoql.catalog.Column;
import io.monoptic.monoql.catalog.SchemaInfo;
import io.monoptic.monoql.catalog.TableInfo;
import io.monoptic.monoql.catalog.standard.StandardCatalog;

import org.apache.calcite.schema.Table;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.sql.DataSource;

/** A catalog whose metadata is durable: every metadata table, with the
 * rules of {@link StandardCatalog}, kept in a SQL database.
 *
 * <p>One database serves many tenants, each with catalogs of its own; a
 * catalog sees the metadata of its tenant and name, and no other.
 *
 * <p>The metadata says what tables there are. Their rows are somewhere
 * else: in a <em>data catalog</em>, of any type, which is told to create,
 * alter and drop each table as this catalog is, and asked for its rows.
 * Without one, tables are metadata only.
 *
 * <p>A statement's changes to the metadata happen entirely or not at all
 * ({@link #atomically}). The data catalog is not part of that: its changes
 * are made last, so that a failure there undoes the metadata; but if the
 * metadata then cannot be committed, what the data catalog did stays
 * done. */
public final class MetastoreCatalog extends StandardCatalog {
  private final Runnable release;
  private final Catalog data;

  /** Creates a catalog.
   *
   * @param dataSource The metastore
   * @param release Called when the catalog is closed, and has no more use
   *     for the data source
   * @param tenant Whose metadata this is
   * @param name The catalog's name, which is part of what its metadata is
   *     stored under: a tenant may have several catalogs
   * @param create Whether to create the metastore's tables if they are
   *     not there
   * @param data Where the rows of tables are; closed with the catalog.
   *     Null if nowhere */
  MetastoreCatalog(DataSource dataSource, Runnable release, String tenant,
      String name, boolean create, Catalog data) {
    super(new SqlStorage(dataSource, tenant, name, create));
    this.release = release;
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

  @Override public void close() {
    try {
      super.close();
      release.run();
    } finally {
      if (data != null) {
        data.close();
      }
    }
  }
}
