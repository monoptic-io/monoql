/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.catalog;

import java.util.List;
import java.util.Map;

/** A row of {@code metadata.MATERIALIZED_VIEWS}.
 *
 * @param schemaName Schema
 * @param viewName Name
 * @param columnNames Column aliases, or empty to use the query's
 * @param definition The query
 * @param schedule Refresh schedule (typically a cron expression), or null
 * @param paused Whether refreshes are paused
 * @param comment Comment, or null
 * @param options Options */
public record MaterializedViewInfo(String schemaName, String viewName,
    List<String> columnNames, String definition, String schedule,
    boolean paused, String comment, Map<String, String> options)
    implements MetaRow {
  public MaterializedViewInfo {
    Rows.required(schemaName, "schemaName");
    Rows.required(viewName, "viewName");
    Rows.required(definition, "definition");
    columnNames = Rows.list(columnNames);
    options = Rows.map(options);
  }

  @Override public Key key() {
    return Key.of(schemaName, viewName);
  }
}
