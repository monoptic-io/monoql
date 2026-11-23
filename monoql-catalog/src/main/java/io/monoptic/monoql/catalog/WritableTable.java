/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.catalog;

import java.util.List;

/** The write side of the data plane: implemented by a
 * {@link org.apache.calcite.schema.Table} returned from
 * {@link Catalog#bind} whose rows the engine may change.
 *
 * <p>For {@code INSERT}, {@code UPDATE} and {@code DELETE} against such a
 * table, the engine computes the affected rows with the ordinary query
 * machinery and passes them here. Rows are arrays in the table's column
 * order, with values in the same representation the table's scan produces.
 *
 * <p>A table that does not implement this interface can still be written to
 * if Calcite knows how, for example a JDBC table, where the whole statement
 * is pushed down to the database.
 *
 * <p>Every operation throws {@link UnsupportedException} by default. */
public interface WritableTable {
  /** Adds rows. */
  default void insert(List<Object[]> rows) {
    throw new UnsupportedException("This table does not support INSERT");
  }

  /** Replaces rows. The lists are parallel: {@code after.get(i)} replaces
   * {@code before.get(i)}. */
  default void update(List<Object[]> before, List<Object[]> after) {
    throw new UnsupportedException("This table does not support UPDATE");
  }

  /** Removes rows. */
  default void delete(List<Object[]> rows) {
    throw new UnsupportedException("This table does not support DELETE");
  }
}
