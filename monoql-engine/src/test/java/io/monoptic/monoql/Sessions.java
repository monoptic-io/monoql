/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Test helpers. */
final class Sessions {
  private Sessions() {
  }

  /** Runs a query and returns each row as a string, e.g. {@code [1, a]}. */
  static List<String> rows(Session session, String sql) {
    final List<String> rows = new ArrayList<>();
    for (Object[] row : session.execute(sql)) {
      rows.add(Arrays.deepToString(row));
    }
    return rows;
  }

  /** Runs statements in order. */
  static void run(Session session, String... statements) {
    for (String sql : statements) {
      session.execute(sql);
    }
  }

  /** Returns the whole of a metadata table. */
  static List<String> dump(Session session, String table) {
    return rows(session, "select * from metadata." + table);
  }
}
