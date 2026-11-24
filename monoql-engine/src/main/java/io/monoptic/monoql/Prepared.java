/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql;

import org.apache.calcite.rel.type.RelDataType;

import java.util.List;
import java.util.function.Function;

/** A statement that has been checked but not run: what it will return, and
 * what it needs to be given.
 *
 * <p>Preparing validates the statement against the catalogs as they are.
 * Each execution validates and plans it again, against the catalogs as they
 * are then; so a prepared statement is a way to learn a statement's shape
 * and to supply values safely, not a cached plan. */
public final class Prepared {
  private final String sql;
  private final RelDataType rowType;
  private final RelDataType parameterType;
  private final Function<Object[], Result> executor;

  /** Creates a prepared statement; called by the engine. */
  public Prepared(String sql, RelDataType rowType, RelDataType parameterType,
      Function<Object[], Result> executor) {
    this.sql = sql;
    this.rowType = rowType;
    this.parameterType = parameterType;
    this.executor = executor;
  }

  public String sql() {
    return sql;
  }

  /** Whether executing the statement returns rows. */
  public boolean isQuery() {
    return rowType != null;
  }

  /** The type of the rows the statement returns; null unless
   * {@link #isQuery()}. */
  public RelDataType rowType() {
    return rowType;
  }

  public List<String> columnNames() {
    return rowType == null ? List.of() : rowType.getFieldNames();
  }

  /** The types of the statement's parameters, the {@code ?}s, in order,
   * as the fields of a row type. */
  public RelDataType parameterType() {
    return parameterType;
  }

  public int parameterCount() {
    return parameterType.getFieldCount();
  }

  /** Executes the statement.
   *
   * @param parameters One value for each {@code ?}. Dates and times are
   *     given as they are returned: {@code LocalDate}, {@code LocalTime},
   *     {@code LocalDateTime}, {@code Instant}. Numbers are converted to
   *     the parameter's type */
  public Result execute(Object... parameters) {
    return executor.apply(parameters);
  }
}
