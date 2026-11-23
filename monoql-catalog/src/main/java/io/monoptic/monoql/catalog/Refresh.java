/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.catalog;

import java.time.Instant;

/** A row of {@code metadata.REFRESHES}: a request to refresh a materialized
 * view now. {@code REFRESH MATERIALIZED VIEW v} inserts one.
 *
 * @param schemaName Schema of the materialized view
 * @param viewName Materialized view
 * @param requestedAt When the refresh was requested */
public record Refresh(String schemaName, String viewName, Instant requestedAt)
    implements MetaRow {
  public Refresh {
    Rows.required(schemaName, "schemaName");
    Rows.required(viewName, "viewName");
  }

  @Override public Key key() {
    return Key.of(schemaName, viewName, requestedAt);
  }
}
