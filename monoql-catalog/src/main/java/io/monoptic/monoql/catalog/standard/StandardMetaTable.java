/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.catalog.standard;

import io.monoptic.monoql.catalog.AlreadyExistsException;
import io.monoptic.monoql.catalog.CatalogException;
import io.monoptic.monoql.catalog.Key;
import io.monoptic.monoql.catalog.MetaRow;
import io.monoptic.monoql.catalog.MetaTable;
import io.monoptic.monoql.catalog.NotFoundException;

import java.util.Optional;

/** A metadata table that supports every operation, over rows kept in a
 * {@link RowStore}.
 *
 * <p>Subclasses add integrity checks by overriding {@link #beforeInsert},
 * {@link #beforeDelete} and {@link #afterChange}. Checks and changes run
 * inside {@link Storage#write}, so what a check saw is still true when the
 * change is made.
 *
 * @param <R> row type */
public class StandardMetaTable<R extends MetaRow> implements MetaTable<R> {
  private final String name;
  private final Storage storage;
  private final RowStore<R> rows;

  public StandardMetaTable(String name, Storage storage, RowStore<R> rows) {
    this.name = name;
    this.storage = storage;
    this.rows = rows;
  }

  @Override public String name() {
    return name;
  }

  @Override public Iterable<R> scan() {
    return rows.scan();
  }

  @Override public Optional<R> get(Key key) {
    return rows.get(key);
  }

  @Override public void insert(R row) {
    storage.write(() -> {
      if (rows.get(row.key()).isPresent()) {
        throw AlreadyExistsException.of(name, row.key());
      }
      beforeInsert(row);
      rows.put(row);
      afterChange(null, row);
    });
  }

  @Override public void update(R before, R after) {
    storage.write(() -> {
      final R current = current(before);
      if (!before.key().equals(after.key())) {
        if (rows.get(after.key()).isPresent()) {
          throw AlreadyExistsException.of(name, after.key());
        }
        beforeInsert(after);
        rows.remove(before.key());
      }
      rows.put(after);
      afterChange(current, after);
    });
  }

  @Override public void delete(R row) {
    storage.write(() -> {
      final R current = current(row);
      beforeDelete(current);
      rows.remove(row.key());
      afterChange(current, null);
    });
  }

  /** Returns the row as it is stored, which must be as the caller last
   * saw it: a change is a change to something, and if that is no longer
   * there to change, someone else got to it first. */
  private R current(R seen) {
    final R current =
        rows.get(seen.key())
            .orElseThrow(() -> NotFoundException.of(name, seen.key()));
    if (!current.equals(seen)) {
      throw new CatalogException(name + ": " + seen.key()
          + " was changed by someone else in the meantime; try again");
    }
    return current;
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
