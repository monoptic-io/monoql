/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.engine;

import io.monoptic.monoql.MonoqlException;
import io.monoptic.monoql.catalog.AlreadyExistsException;
import io.monoptic.monoql.catalog.Catalog;
import io.monoptic.monoql.catalog.CatalogException;
import io.monoptic.monoql.catalog.Column;
import io.monoptic.monoql.catalog.DataTypes;
import io.monoptic.monoql.catalog.Key;
import io.monoptic.monoql.catalog.MaterializedViewInfo;
import io.monoptic.monoql.catalog.MetaTable;
import io.monoptic.monoql.catalog.NotFoundException;
import io.monoptic.monoql.catalog.TableInfo;
import io.monoptic.monoql.catalog.UnsupportedException;
import io.monoptic.monoql.catalog.ViewInfo;

import org.apache.calcite.rel.type.RelDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;

/** {@code COLUMNS}, as a view over a catalog's {@code TABLES} and its
 * views.
 *
 * <p>Reads flatten the columns of each table, and derive those of each
 * view and materialized view from its definition. Writes are rewritten as an
 * update of the owning table's row: inserting a column is
 * {@code ALTER TABLE ... ADD COLUMN}, deleting one is
 * {@code ... DROP COLUMN}. */
final class ColumnsTable implements MetaTable<ColumnInfo> {
  static final String NAME = "COLUMNS";

  private final String catalogName;
  private final Catalog catalog;
  private final MetaTable<TableInfo> tables;
  private final BiFunction<String, List<String>, RelDataType> typeDeriver;

  ColumnsTable(String catalogName, Catalog catalog,
      BiFunction<String, List<String>, RelDataType> typeDeriver) {
    this.catalogName = catalogName;
    this.catalog = catalog;
    this.tables = catalog.tables();
    this.typeDeriver = typeDeriver;
  }

  @Override public String name() {
    return NAME;
  }

  @Override public Iterable<ColumnInfo> scan() {
    final List<ColumnInfo> rows = new ArrayList<>();
    UnsupportedException unsupported = null;
    try {
      for (TableInfo table : tables.scan()) {
        add(rows, table.schemaName(), table.tableName(), table.columns());
      }
    } catch (UnsupportedException e) {
      unsupported = e;
    }
    try {
      for (ViewInfo view : catalog.views().scan()) {
        addView(rows, view.schemaName(), view.viewName(), view.columnNames(),
            view.definition());
      }
      unsupported = null;
    } catch (UnsupportedException ignored) {
      // A catalog need not have views.
    }
    try {
      for (MaterializedViewInfo view : catalog.materializedViews().scan()) {
        addView(rows, view.schemaName(), view.viewName(), view.columnNames(),
            view.definition());
      }
      unsupported = null;
    } catch (UnsupportedException ignored) {
      // Nor materialized views.
    }
    if (unsupported != null) {
      throw unsupported;
    }
    return rows;
  }

  private static void add(List<ColumnInfo> rows, String schemaName,
      String tableName, List<Column> columns) {
    int ordinal = 1;
    for (Column c : columns) {
      rows.add(
          new ColumnInfo(schemaName, tableName, c.columnName(), ordinal++,
              c.dataType(), c.nullable(), c.comment()));
    }
  }

  /** Adds the columns of a view, which are whatever its definition yields
   * today. A view whose definition is no longer valid (say, because a table
   * it reads was dropped) has none. */
  private void addView(List<ColumnInfo> rows, String schemaName,
      String viewName, List<String> columnNames, String definition) {
    final RelDataType rowType;
    try {
      rowType =
          new ViewTable(definition, columnNames,
              List.of(catalogName, schemaName),
              List.of(catalogName, schemaName, viewName), typeDeriver)
              .rowType();
    } catch (MonoqlException | CatalogException e) {
      return;
    }
    add(rows, schemaName, viewName, DataTypes.columns(rowType));
  }

  private boolean isView(Key key) {
    try {
      if (catalog.views().get(key).isPresent()) {
        return true;
      }
    } catch (UnsupportedException ignored) {
      // Fall through.
    }
    try {
      return catalog.materializedViews().get(key).isPresent();
    } catch (UnsupportedException ignored) {
      return false;
    }
  }

  private TableInfo table(ColumnInfo row) {
    final Key key = Key.of(row.schemaName(), row.tableName());
    final Optional<TableInfo> table = tables.get(key);
    if (table.isEmpty() && isView(key)) {
      throw new UnsupportedException("The columns of view " + key
          + " follow from its definition, and cannot be changed");
    }
    return table.orElseThrow(() -> NotFoundException.of(tables.name(), key));
  }

  private static int indexOf(TableInfo table, String columnName) {
    for (int i = 0; i < table.columns().size(); i++) {
      if (table.columns().get(i).columnName().equals(columnName)) {
        return i;
      }
    }
    return -1;
  }

  private static Column column(ColumnInfo row) {
    if (row.columnName() == null || row.dataType() == null) {
      throw new CatalogException("COLUMN_NAME and DATA_TYPE are required");
    }
    return new Column(row.columnName(), row.dataType(),
        row.nullable() == null || row.nullable(), row.comment());
  }

  private static void place(List<Column> columns, Column column,
      Integer ordinal) {
    if (ordinal == null || ordinal > columns.size()) {
      columns.add(column);
    } else {
      columns.add(Math.max(ordinal, 1) - 1, column);
    }
  }

  @Override public void insert(ColumnInfo row) {
    final TableInfo table = table(row);
    if (indexOf(table, row.columnName()) >= 0) {
      throw AlreadyExistsException.of(NAME, row.key());
    }
    final List<Column> columns = new ArrayList<>(table.columns());
    place(columns, column(row), row.ordinalPosition());
    tables.update(table, table.withColumns(columns));
  }

  @Override public void update(ColumnInfo before, ColumnInfo after) {
    final TableInfo table = table(before);
    if (!Key.of(before.schemaName(), before.tableName())
        .equals(Key.of(after.schemaName(), after.tableName()))) {
      throw new CatalogException("Cannot move a column to another table");
    }
    final int i = indexOf(table, before.columnName());
    if (i < 0) {
      throw NotFoundException.of(NAME, before.key());
    }
    if (!before.columnName().equals(after.columnName())
        && indexOf(table, after.columnName()) >= 0) {
      throw AlreadyExistsException.of(NAME, after.key());
    }
    final List<Column> columns = new ArrayList<>(table.columns());
    columns.remove(i);
    // Keep the position unless the update moved it.
    place(columns, column(after),
        java.util.Objects.equals(before.ordinalPosition(), after.ordinalPosition())
            ? Integer.valueOf(i + 1) : after.ordinalPosition());
    final String oldName = before.columnName();
    final String newName = after.columnName();
    tables.update(table,
        new TableInfo(table.schemaName(), table.tableName(), columns,
            rename(table.primaryKey(), oldName, newName),
            rename(table.partitionKeys(), oldName, newName),
            table.comment(), table.options()));
  }

  private static List<String> rename(List<String> names, String oldName,
      String newName) {
    return names.stream().map(n -> n.equals(oldName) ? newName : n).toList();
  }

  @Override public void delete(ColumnInfo row) {
    final TableInfo table = table(row);
    final int i = indexOf(table, row.columnName());
    if (i < 0) {
      throw NotFoundException.of(NAME, row.key());
    }
    if (table.primaryKey().contains(row.columnName())
        || table.partitionKeys().contains(row.columnName())) {
      throw new CatalogException("Column " + row.columnName()
          + " is part of a key of " + table.key());
    }
    final List<Column> columns = new ArrayList<>(table.columns());
    columns.remove(i);
    tables.update(table, table.withColumns(columns));
  }
}
