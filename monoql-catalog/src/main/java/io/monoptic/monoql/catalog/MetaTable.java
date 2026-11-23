/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.catalog;

import java.util.Optional;

/** A table of metadata: the unit of the catalog SPI.
 *
 * <p>Every operation throws {@link UnsupportedException} by default. An
 * implementation overrides the operations its backend supports and leaves the
 * rest alone; for instance a read-only catalog implements only {@link #scan}.
 *
 * <p>Statements and DML are two spellings of the same calls. {@code CREATE}
 * and {@code GRANT} statements call {@link #insert}; {@code DROP} and
 * {@code REVOKE} call {@link #delete}; {@code ALTER}, {@code PAUSE},
 * {@code RESUME} and {@code COMMENT} call {@link #update}; {@code SHOW} and
 * {@code DESCRIBE} call {@link #scan}. So does the equivalent
 * {@code INSERT}, {@code DELETE}, {@code UPDATE} or {@code SELECT} against the
 * {@code metadata} schema.
 *
 * @param <R> row type
 */
public interface MetaTable<R extends MetaRow> {
  /** Name of this table, as it appears in the {@code metadata} schema. */
  String name();

  /** Returns all rows. */
  default Iterable<R> scan() {
    throw UnsupportedException.of(name(), "SELECT");
  }

  /** Returns the row with a given key, if present.
   *
   * <p>The default implementation scans. Backends that can do point lookups
   * should override, because name resolution during planning uses this. */
  default Optional<R> get(Key key) {
    for (R row : scan()) {
      if (row.key().equals(key)) {
        return Optional.of(row);
      }
    }
    return Optional.empty();
  }

  /** Adds a row.
   *
   * @throws AlreadyExistsException if a row with the same key exists */
  default void insert(R row) {
    throw UnsupportedException.of(name(), "INSERT");
  }

  /** Replaces a row. The key may change, which is how renames are expressed.
   *
   * @throws NotFoundException if {@code before} does not exist */
  default void update(R before, R after) {
    throw UnsupportedException.of(name(), "UPDATE");
  }

  /** Removes a row.
   *
   * @throws NotFoundException if the row does not exist */
  default void delete(R row) {
    throw UnsupportedException.of(name(), "DELETE");
  }

  /** Returns a table that supports nothing. */
  static <R extends MetaRow> MetaTable<R> unsupported(String name) {
    return () -> name;
  }
}
