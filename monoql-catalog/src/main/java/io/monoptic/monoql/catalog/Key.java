/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.catalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Identity of a metadata row: the values of its key columns, in order.
 *
 * <p>Two rows of the same metadata table with equal keys are the same object
 * (the same table, the same grant, ...), whatever their other columns say. */
public record Key(List<Object> parts) {
  public Key {
    // Parts may be null (e.g. the schema of a catalog-level grant).
    parts = Collections.unmodifiableList(new ArrayList<>(parts));
  }

  public static Key of(Object... parts) {
    final List<Object> list = new ArrayList<>(parts.length);
    Collections.addAll(list, parts);
    return new Key(list);
  }

  @Override public String toString() {
    final StringBuilder b = new StringBuilder();
    for (Object part : parts) {
      if (b.length() > 0) {
        b.append('.');
      }
      b.append(part);
    }
    return b.toString();
  }
}
