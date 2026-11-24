/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.catalog.standard;

import io.monoptic.monoql.catalog.MetaRow;
import io.monoptic.monoql.catalog.MetaTables;

import java.util.function.Supplier;

/** Where a {@link StandardCatalog} keeps its metadata: a {@link RowStore}
 * for each table, and a way of changing several of them as one.
 *
 * <p>A catalog's rules read before they write: a table may be created only
 * in a schema that exists, a schema dropped only when nothing is in it. So
 * that two writers cannot each pass a check the other is about to
 * invalidate, changes are made inside {@link #write}, one writer at a
 * time. */
public interface Storage extends AutoCloseable {
  /** Returns the store of a metadata table. */
  <R extends MetaRow> RowStore<R> rows(MetaTables.Def<R> def);

  /** Runs some work as a unit: if it throws, the changes it made here are
   * undone, if this storage can undo; and until it returns, nobody else
   * sees them. A unit inside a unit is part of it.
   *
   * <p>See {@link io.monoptic.monoql.catalog.Catalog#atomically}. */
  <T> T atomically(Supplier<T> work);

  /** Runs changes, and the reads they depend on, with no other writer's
   * changes in between; as part of the current unit, or as a unit of their
   * own. Once a unit has written, it is the only writer until it ends. */
  void write(Runnable work);

  @Override default void close() {
  }
}
