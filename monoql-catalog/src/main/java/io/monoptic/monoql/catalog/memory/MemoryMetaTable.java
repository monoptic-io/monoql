/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.catalog.memory;

import io.monoptic.monoql.catalog.AlreadyExistsException;
import io.monoptic.monoql.catalog.Key;
import io.monoptic.monoql.catalog.MetaRow;
import io.monoptic.monoql.catalog.MetaTable;
import io.monoptic.monoql.catalog.NotFoundException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** A metadata table held in a map. Supports every operation.
 *
 * <p>Useful on its own to back one table of an otherwise unsupported
 * catalog. Subclasses add integrity checks by overriding
 * {@link #beforeInsert}, {@link #beforeDelete} and {@link #afterChange}.
 *
 * @param <R> row type */
public class MemoryMetaTable<R extends MetaRow> implements MetaTable<R> {
  private final String name;
  private final Map<Key, R> rows = new LinkedHashMap<>();

  public MemoryMetaTable(String name) {
    this.name = name;
  }

  @Override public String name() {
    return name;
  }

  @Override public synchronized Iterable<R> scan() {
    return new ArrayList<>(rows.values());
  }

  @Override public synchronized Optional<R> get(Key key) {
    return Optional.ofNullable(rows.get(key));
  }

  @Override public synchronized void insert(R row) {
    if (rows.containsKey(row.key())) {
      throw AlreadyExistsException.of(name, row.key());
    }
    beforeInsert(row);
    rows.put(row.key(), row);
    afterChange(null, row);
  }

  @Override public synchronized void update(R before, R after) {
    if (!rows.containsKey(before.key())) {
      throw NotFoundException.of(name, before.key());
    }
    final R current = rows.get(before.key());
    if (!before.key().equals(after.key())) {
      if (rows.containsKey(after.key())) {
        throw AlreadyExistsException.of(name, after.key());
      }
      beforeInsert(after);
      // Preserve position as far as a LinkedHashMap allows: re-insert.
      rows.remove(before.key());
    }
    rows.put(after.key(), after);
    afterChange(current, after);
  }

  @Override public synchronized void delete(R row) {
    final R current = rows.get(row.key());
    if (current == null) {
      throw NotFoundException.of(name, row.key());
    }
    beforeDelete(current);
    rows.remove(row.key());
    afterChange(current, null);
  }

  /** Called before a row appears under a new key; throw to reject it. */
  protected void beforeInsert(R row) {
  }

  /** Called before a row is removed; throw to reject the removal. */
  protected void beforeDelete(R row) {
  }

  /** Called after any change. Exactly one of the arguments may be null. */
  protected void afterChange(R before, R after) {
  }
}
