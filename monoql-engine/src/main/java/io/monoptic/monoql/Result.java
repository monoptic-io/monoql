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
import java.util.ArrayList;
import java.util.List;

/** The outcome of a statement: either rows, or a count of rows affected. */
public final class Result {
  private final RelDataType rowType;
  private final List<Object[]> rows;
  private final long updateCount;

  private Result(RelDataType rowType, List<Object[]> rows, long updateCount) {
    this.rowType = rowType;
    this.rows = rows;
    this.updateCount = updateCount;
  }

  /** Creates the result of a query. Values are converted from the engine's
   * internal representation to java.time and friends. */
  public static Result ofRows(RelDataType rowType, List<Object[]> rows) {
    final List<RelDataTypeField> fields = rowType.getFieldList();
    final List<Object[]> converted = new ArrayList<>(rows.size());
    for (Object[] row : rows) {
      final Object[] r = new Object[row.length];
      for (int i = 0; i < r.length; i++) {
        r[i] = external(fields.get(i).getType().getSqlTypeName(), row[i]);
      }
      converted.add(r);
    }
    return new Result(rowType, converted, -1);
  }

  /** Creates the result of DML, or of a statement that lowers to DML. */
  public static Result ofCount(long updateCount) {
    return new Result(null, List.of(), updateCount);
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

  public List<Object[]> rows() {
    return rows;
  }

  /** Rows affected; -1 if {@link #isQuery()}. */
  public long updateCount() {
    return updateCount;
  }
}
