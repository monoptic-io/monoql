/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.catalog.memory;

import io.monoptic.monoql.catalog.Column;
import io.monoptic.monoql.catalog.DataTypes;
import io.monoptic.monoql.catalog.WritableTable;

import org.apache.calcite.DataContext;
import org.apache.calcite.linq4j.AbstractEnumerable;
import org.apache.calcite.linq4j.DelegatingEnumerator;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Enumerator;
import org.apache.calcite.linq4j.Linq4j;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.ScannableTable;
import org.apache.calcite.schema.impl.AbstractTable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Rows of an in-memory table. */
final class MemoryDataTable extends AbstractTable
    implements ScannableTable, WritableTable {
  private List<Column> columns;
  private final List<Object[]> rows = new ArrayList<>();
  /** The unit the calling thread is in, if any. */
  private final ThreadLocal<Unit> units;

  MemoryDataTable(List<Column> columns, ThreadLocal<Unit> units) {
    this.columns = columns;
    this.units = units;
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

  /** Scans a snapshot, so that a write never sees itself; though a later
   * step of the same statement sees what the earlier steps wrote. The scan
   * stops if the statement is cancelled. */
  @Override public synchronized Enumerable<Object[]> scan(DataContext root) {
    final List<Object[]> snapshot = new ArrayList<>(visible());
    final AtomicBoolean cancel = DataContext.Variable.CANCEL_FLAG.get(root);
    return new AbstractEnumerable<>() {
      @Override public Enumerator<Object[]> enumerator() {
        final Enumerator<Object[]> e = Linq4j.enumerator(snapshot);
        return new DelegatingEnumerator<>(e) {
          @Override public boolean moveNext() {
            if (cancel != null && cancel.get()) {
              throw new CancellationException("Statement cancelled");
            }
            return super.moveNext();
          }
        };
      }
    };
  }

  @Override public void insert(List<Object[]> newRows) {
    final List<Object[]> copies = new ArrayList<>();
    for (Object[] row : newRows) {
      copies.add(row.clone());
    }
    write(rows -> rows.addAll(copies));
  }

  private static int indexOf(List<Object[]> rows, Object[] row) {
    for (int i = 0; i < rows.size(); i++) {
      if (Arrays.deepEquals(rows.get(i), row)) {
        return i;
      }
    }
    return -1;
  }

  @Override public void update(List<Object[]> before, List<Object[]> after) {
    write(rows -> {
      for (int i = 0; i < before.size(); i++) {
        final int index = indexOf(rows, before.get(i));
        if (index >= 0) {
          rows.set(index, after.get(i).clone());
        }
      }
    });
  }

  @Override public void delete(List<Object[]> oldRows) {
    write(rows -> {
      for (Object[] row : oldRows) {
        final int index = indexOf(rows, row);
        if (index >= 0) {
          rows.remove(index);
        }
      }
    });
  }

  /** Makes a change: at once, or, inside a unit, to the unit's own copy
   * of the rows, to be made again here when the unit ends well. */
  private synchronized void write(Consumer<List<Object[]>> change) {
    final Unit unit = units.get();
    if (unit == null) {
      change.accept(rows);
      return;
    }
    final Pending pending =
        unit.pending.computeIfAbsent(this,
            t -> new Pending(new ArrayList<>(rows)));
    change.accept(pending.rows);
    pending.changes.add(change);
  }

  /** The rows as the calling thread should see them. */
  private List<Object[]> visible() {
    final Unit unit = units.get();
    final Pending pending = unit == null ? null : unit.pending.get(this);
    return pending == null ? rows : pending.rows;
  }

  /** The changes a statement has made to tables, which nobody else sees
   * until it ends, and nobody ever if it fails. */
  static final class Unit {
    private final Map<MemoryDataTable, Pending> pending =
        new LinkedHashMap<>();

    /** Makes the changes for all to see. Each table changes at once;
     * changes that others made to it meanwhile are kept. */
    void publish() {
      pending.forEach((table, p) -> {
        synchronized (table) {
          p.changes.forEach(change -> change.accept(table.rows));
        }
      });
    }
  }

  /** A unit's copy of a table's rows, and what it did to them. */
  private record Pending(List<Object[]> rows,
      List<Consumer<List<Object[]>>> changes) {
    Pending(List<Object[]> rows) {
      this(rows, new ArrayList<>());
    }
  }
}
