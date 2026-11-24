/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.catalog.standard;

import io.monoptic.monoql.catalog.Key;
import io.monoptic.monoql.catalog.MetaRow;

import java.util.List;
import java.util.Optional;

/** Where the rows of one metadata table are kept: a map from key to row,
 * and nothing more. What a row may say, and what else must change when it
 * does, is not the store's business; see {@link StandardMetaTable}.
 *
 * @param <R> row type */
public interface RowStore<R extends MetaRow> {
  /** Returns every row; the caller may keep the list. */
  List<R> scan();

  Optional<R> get(Key key);

  /** Stores a row under its key, replacing any row already there. */
  void put(R row);

  void remove(Key key);
}
