/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.sql.type.SqlTypeName;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/** The outcome of a statement: either rows, or a count of rows affected.
 *
 * <p>The rows of a query are not held in memory; they are produced as they
 * are read, once, in order. A result holds resources (for a table in
 * another database, a connection) until its last row has been read or it is
 * closed, so use try-with-resources unless you read every row. */
public final class Result implements Iterable<Object[]>, AutoCloseable {
  private final RelDataType rowType;
  private final long updateCount;
  /** Where rows come from, until they have all been read; then null. */
  private Iterator<Object[]> source;
  private AutoCloseable resource;
  private boolean iterating;

  private Result(RelDataType rowType, Iterator<Object[]> source,
      AutoCloseable resource, long updateCount) {
    this.rowType = rowType;
    this.source = source;
    this.resource = resource;
    this.updateCount = updateCount;
  }

  /** Creates the result of a query from rows in the engine's internal
   * representation. Values are converted to java.time and friends as they
   * are read.
   *
   * @param resource Closed when the last row has been read, or when the
   *     result is closed; may be null */
  public static Result ofRows(RelDataType rowType, Iterator<Object[]> rows,
      AutoCloseable resource) {
    return new Result(rowType, rows, resource, -1);
  }

  /** Creates the result of DML, or of a statement that lowers to DML. */
  public static Result ofCount(long updateCount) {
    return new Result(null, null, null, updateCount);
  }

  private static Object external(SqlTypeName type, Object value) {
    if (value == null) {
      return null;
    }
    switch (type) {
    case DATE:
      return value instanceof Number n ? LocalDate.ofEpochDay(n.longValue())
          : value;
    case TIME:
      return value instanceof Number n
          ? LocalTime.ofNanoOfDay(n.longValue() * 1_000_000L) : value;
    case TIMESTAMP:
      return value instanceof Number n
          ? LocalDateTime.ofInstant(Instant.ofEpochMilli(n.longValue()),
              ZoneOffset.UTC)
          : value;
    case TIMESTAMP_WITH_LOCAL_TIME_ZONE:
      return value instanceof Number n ? Instant.ofEpochMilli(n.longValue())
          : value;
    default:
      return value;
    }
  }

  /** Whether this result has rows (even if zero of them). */
  public boolean isQuery() {
    return rowType != null;
  }

  /** The row type; null unless {@link #isQuery()}. */
  public RelDataType rowType() {
    return rowType;
  }

  public List<String> columnNames() {
    return rowType == null ? List.of() : rowType.getFieldNames();
  }

  /** Returns an iterator over the rows; there is only one, because rows
   * can be read only once. Empty unless {@link #isQuery()}. */
  @Override public Iterator<Object[]> iterator() {
    if (iterating) {
      throw new IllegalStateException(
          "The rows of this result have already been read");
    }
    iterating = true;
    final List<RelDataTypeField> fields =
        rowType == null ? List.of() : rowType.getFieldList();
    return new Iterator<>() {
      @Override public boolean hasNext() {
        if (source != null && !source.hasNext()) {
          close();
        }
        return source != null;
      }

      @Override public Object[] next() {
        if (!hasNext()) {
          throw new NoSuchElementException();
        }
        final Object[] row = source.next();
        final Object[] r = new Object[row.length];
        for (int i = 0; i < r.length; i++) {
          r[i] = external(fields.get(i).getType().getSqlTypeName(), row[i]);
        }
        return r;
      }
    };
  }

  /** Releases whatever the result holds; rows not yet read are lost. Does
   * nothing if the rows have all been read. */
  @Override public void close() {
    source = null;
    final AutoCloseable r = resource;
    resource = null;
    if (r != null) {
      try {
        r.close();
      } catch (RuntimeException e) {
        throw e;
      } catch (Exception e) {
        throw new MonoqlException(e.getMessage(), e);
      }
    }
  }

  /** Rows affected; -1 if {@link #isQuery()}. */
  public long updateCount() {
    return updateCount;
  }
}
