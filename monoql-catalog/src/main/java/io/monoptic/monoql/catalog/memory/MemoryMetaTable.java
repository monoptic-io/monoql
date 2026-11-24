/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.catalog.memory;

import io.monoptic.monoql.catalog.MetaRow;
import io.monoptic.monoql.catalog.standard.HeapStorage;
import io.monoptic.monoql.catalog.standard.StandardMetaTable;

/** A metadata table held in a map. Supports every operation.
 *
 * <p>Useful on its own to back one table of an otherwise unsupported
 * catalog. Subclasses add integrity checks by overriding
 * {@link #beforeInsert}, {@link #beforeDelete} and {@link #afterChange}.
 *
 * @param <R> row type */
public class MemoryMetaTable<R extends MetaRow> extends StandardMetaTable<R> {
  public MemoryMetaTable(String name) {
    this(name, new HeapStorage());
  }

  private MemoryMetaTable(String name, HeapStorage storage) {
    super(name, storage, storage.rows());
  }
}
