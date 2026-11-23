/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.engine;

import io.monoptic.monoql.catalog.AlreadyExistsException;
import io.monoptic.monoql.catalog.CatalogException;
import io.monoptic.monoql.catalog.Column;
import io.monoptic.monoql.catalog.Key;
import io.monoptic.monoql.catalog.MetaTable;
import io.monoptic.monoql.catalog.NotFoundException;
import io.monoptic.monoql.catalog.TableInfo;

import java.util.ArrayList;
import java.util.List;

/** {@code COLUMNS}, as a view over a catalog's {@code TABLES}.
 *
 * <p>Reads flatten the columns of each table. Writes are rewritten as an
 * update of the owning table's row: inserting a column is
 * {@code ALTER TABLE ... ADD COLUMN}, deleting one is
 * {@code ... DROP COLUMN}. */
final class ColumnsTable implements MetaTable<ColumnInfo> {
  static final String NAME = "COLUMNS";

  private final MetaTable<TableInfo> tables;

  ColumnsTable(MetaTable<TableInfo> tables) {
    this.tables = tables;
  }

  @Override public String name() {
    return NAME;
  }

  @Override public Iterable<ColumnInfo> scan() {
    final List<ColumnInfo> rows = new ArrayList<>();
    for (TableInfo table : tables.scan()) {
      int ordinal = 1;
      for (Column c : table.columns()) {
        rows.add(
            new ColumnInfo(table.schemaName(), table.tableName(),
                c.columnName(), ordinal++, c.dataType(), c.nullable(),
                c.comment()));
      }
    }
    return rows;
  }

  private TableInfo table(ColumnInfo row) {
    final Key key = Key.of(row.schemaName(), row.tableName());
    return tables.get(key)
        .orElseThrow(() -> NotFoundException.of(tables.name(), key));
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
