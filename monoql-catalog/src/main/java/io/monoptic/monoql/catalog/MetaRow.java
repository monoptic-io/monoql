/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.catalog;

/** A row of a {@link MetaTable}. Rows are immutable records. */
public interface MetaRow {
  /** Returns the identity of this row within its table. */
  Key key();
}
