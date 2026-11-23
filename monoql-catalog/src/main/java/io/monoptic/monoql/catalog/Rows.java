/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.catalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Helpers for the canonical constructors of row records. */
final class Rows {
  private Rows() {
  }

  /** Copies a list; null means empty. */
  static <E> List<E> list(List<E> list) {
    return list == null || list.isEmpty()
        ? List.of()
        : Collections.unmodifiableList(new ArrayList<>(list));
  }

  /** Copies a map, preserving order; null means empty. */
  static Map<String, String> map(Map<String, String> map) {
    return map == null || map.isEmpty()
        ? Map.of()
        : Collections.unmodifiableMap(new LinkedHashMap<>(map));
  }

  static String required(String value, String what) {
    if (value == null || value.isEmpty()) {
      throw new IllegalArgumentException(what + " is required");
    }
    return value;
  }
}
