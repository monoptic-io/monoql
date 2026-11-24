/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.catalog.memory;

import io.monoptic.monoql.catalog.Key;
import io.monoptic.monoql.catalog.TableInfo;
import io.monoptic.monoql.catalog.standard.HeapStorage;
import io.monoptic.monoql.catalog.standard.StandardCatalog;

import org.apache.calcite.schema.Table;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** A catalog that lives on the heap and supports everything: the rules of
 * {@link StandardCatalog}, with metadata and rows held in memory. */
public class MemoryCatalog extends StandardCatalog {
  private final Map<Key, MemoryDataTable> data = new ConcurrentHashMap<>();

  public MemoryCatalog() {
    super(new HeapStorage());
  }

  /** Existing rows survive a change of shape, and follow a rename. */
  @Override protected void tableChanged(TableInfo before, TableInfo after) {
    if (before == null) {
      return;
    }
    final MemoryDataTable store = data.remove(before.key());
    if (after != null && store != null) {
      store.alter(after.columns());
      data.put(after.key(), store);
    }
  }

  @Override public Optional<Table> bind(TableInfo table) {
    return Optional.of(
        data.computeIfAbsent(table.key(),
            k -> new MemoryDataTable(table.columns())));
  }
}
