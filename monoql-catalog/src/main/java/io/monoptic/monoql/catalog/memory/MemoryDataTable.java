/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.catalog.memory;

import io.monoptic.monoql.catalog.Column;
import io.monoptic.monoql.catalog.DataTypes;
import io.monoptic.monoql.catalog.WritableTable;

import org.apache.calcite.DataContext;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Linq4j;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.ScannableTable;
import org.apache.calcite.schema.impl.AbstractTable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Rows of an in-memory table. */
final class MemoryDataTable extends AbstractTable
    implements ScannableTable, WritableTable {
  private List<Column> columns;
  private final List<Object[]> rows = new ArrayList<>();

  MemoryDataTable(List<Column> columns) {
    this.columns = columns;
  }

  /** Changes the columns, carrying existing rows across by column name. */
  synchronized void alter(List<Column> newColumns) {
    if (newColumns.equals(columns)) {
      return;
    }
    final int[] sources = new int[newColumns.size()];
    for (int i = 0; i < sources.length; i++) {
      sources[i] = -1;
      for (int j = 0; j < columns.size(); j++) {
        if (columns.get(j).columnName()
            .equals(newColumns.get(i).columnName())) {
          sources[i] = j;
        }
      }
    }
    final List<Object[]> newRows = new ArrayList<>();
    for (Object[] row : rows) {
      final Object[] newRow = new Object[sources.length];
      for (int i = 0; i < sources.length; i++) {
        newRow[i] = sources[i] < 0 ? null : row[sources[i]];
      }
      newRows.add(newRow);
    }
    rows.clear();
    rows.addAll(newRows);
    columns = newColumns;
  }

  @Override public synchronized RelDataType getRowType(
      RelDataTypeFactory typeFactory) {
    return DataTypes.rowType(columns, typeFactory);
  }

  /** Scans a snapshot, so that a statement never sees its own writes. */
  @Override public synchronized Enumerable<Object[]> scan(DataContext root) {
    return Linq4j.asEnumerable(new ArrayList<>(rows));
  }

  @Override public synchronized void insert(List<Object[]> newRows) {
    for (Object[] row : newRows) {
      rows.add(row.clone());
    }
  }

  private int indexOf(Object[] row) {
    for (int i = 0; i < rows.size(); i++) {
      if (Arrays.deepEquals(rows.get(i), row)) {
        return i;
      }
    }
    return -1;
  }

  @Override public synchronized void update(List<Object[]> before,
      List<Object[]> after) {
    for (int i = 0; i < before.size(); i++) {
      final int index = indexOf(before.get(i));
      if (index >= 0) {
        rows.set(index, after.get(i).clone());
      }
    }
  }

  @Override public synchronized void delete(List<Object[]> oldRows) {
    for (Object[] row : oldRows) {
      final int index = indexOf(row);
      if (index >= 0) {
        rows.remove(index);
      }
    }
  }
}
