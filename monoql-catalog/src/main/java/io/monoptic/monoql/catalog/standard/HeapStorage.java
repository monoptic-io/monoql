/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.catalog.standard;

import io.monoptic.monoql.catalog.Key;
import io.monoptic.monoql.catalog.MetaRow;
import io.monoptic.monoql.catalog.MetaTables;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/** Storage on the heap. Writers take turns; nothing is undone, so a unit
 * that fails part of the way through has happened part of the way. */
public final class HeapStorage implements Storage {
  private final Map<String, Rows<?>> tables = new LinkedHashMap<>();

  @SuppressWarnings("unchecked")
  @Override public synchronized <R extends MetaRow> RowStore<R> rows(
      MetaTables.Def<R> def) {
    return (RowStore<R>) tables.computeIfAbsent(def.name(), n -> new Rows<>());
  }

  /** Returns a store that belongs to no table in particular. */
  public <R extends MetaRow> RowStore<R> rows() {
    return new Rows<>();
  }

  @Override public <T> T atomically(Supplier<T> work) {
    return work.get();
  }

  @Override public synchronized void write(Runnable work) {
    work.run();
  }

  /** Rows in the order they were first stored. */
  private static final class Rows<R extends MetaRow> implements RowStore<R> {
    private final Map<Key, R> rows = new LinkedHashMap<>();

    @Override public synchronized List<R> scan() {
      return new ArrayList<>(rows.values());
    }

    @Override public synchronized Optional<R> get(Key key) {
      return Optional.ofNullable(rows.get(key));
    }

    @Override public synchronized void put(R row) {
      rows.put(row.key(), row);
    }

    @Override public synchronized void remove(Key key) {
      rows.remove(key);
    }
  }
}
