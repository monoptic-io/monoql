/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.engine;

import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/** Writes fragments of SQL text: quoted identifiers and literals. */
final class SqlText {
  private SqlText() {
  }

  /** Quotes an identifier. */
  static String id(String name) {
    return '"' + name.replace("\"", "\"\"") + '"';
  }

  /** Quotes a dotted name. */
  static String path(String... names) {
    final StringJoiner j = new StringJoiner(".");
    for (String name : names) {
      j.add(id(name));
    }
    return j.toString();
  }

  /** A character literal, or NULL. */
  static String str(String value) {
    return value == null ? "NULL" : "'" + value.replace("'", "''") + "'";
  }

  static String bool(boolean value) {
    return value ? "TRUE" : "FALSE";
  }

  /** An array of character literals; NULL if empty. */
  static String array(List<String> values) {
    if (values.isEmpty()) {
      return "NULL";
    }
    final StringJoiner j = new StringJoiner(", ", "ARRAY[", "]");
    for (String value : values) {
      j.add(str(value));
    }
    return j.toString();
  }

  /** A map of character literals; NULL if empty. */
  static String map(Map<String, String> map) {
    if (map.isEmpty()) {
      return "NULL";
    }
    final StringJoiner j = new StringJoiner(", ", "MAP[", "]");
    for (Map.Entry<String, String> e : map.entrySet()) {
      j.add(str(e.getKey()));
      j.add(str(e.getValue()));
    }
    return j.toString();
  }

  /** {@code column = 'value'}, or {@code column IS NULL}. */
  static String eq(String column, String value) {
    return value == null ? id(column) + " IS NULL"
        : id(column) + " = " + str(value);
  }
}
