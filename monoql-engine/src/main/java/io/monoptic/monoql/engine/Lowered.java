/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.engine;

import java.util.List;
import java.util.Map;

/** What a statement means: a sequence of DML statements or queries against
 * the {@code METADATA} schema (and, for {@code CREATE TABLE AS}, the new
 * table).
 *
 * <p>Running the steps by hand has the same effect as running the statement.
 * What a statement adds is strictness about existence: {@code DROP TABLE t}
 * fails if there is no {@code t}, where the bare {@code DELETE} would quietly
 * delete nothing. */
public record Lowered(List<Step> steps) {
  public Lowered {
    steps = List.copyOf(steps);
  }

  /** One step.
   *
   * @param sql The DML or query
   * @param ignoreConflict Whether a row that already exists is skipped
   *     rather than an error ({@code IF NOT EXISTS})
   * @param notFound If not null, the step must affect at least one row, and
   *     this is the error if it does not
   * @param catalog If not null, the one catalog the step is aimed at
   * @param table If {@code catalog} is not null, the metadata table
   * @param doomed If not null, the step is a {@code DELETE}, and these are
   *     the rows it names, each a map from column to value. A catalog that
   *     can remove rows but cannot list them (one that can revoke a grant,
   *     say, but not enumerate grants) is asked to delete exactly these */
  public record Step(String sql, boolean ignoreConflict, String notFound,
      String catalog, String table, List<Map<String, Object>> doomed) {
    Step(String sql, boolean ignoreConflict, String notFound) {
      this(sql, ignoreConflict, notFound, null, null, null);
    }
  }

  static Lowered of(String sql) {
    return new Lowered(List.of(new Step(sql, false, null)));
  }

  /** Returns the SQL of the steps. */
  public List<String> sql() {
    return steps.stream().map(Step::sql).toList();
  }
}
