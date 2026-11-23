/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.catalog;

import java.util.List;

/** A row of {@code metadata.VIEWS}.
 *
 * @param schemaName Schema
 * @param viewName Name
 * @param columnNames Column aliases, or empty to use the query's
 * @param definition The query. Unqualified names in it resolve relative to
 *     the view's own catalog and schema
 * @param comment Comment, or null */
public record ViewInfo(String schemaName, String viewName,
    List<String> columnNames, String definition, String comment)
    implements MetaRow {
  public ViewInfo {
    Rows.required(schemaName, "schemaName");
    Rows.required(viewName, "viewName");
    Rows.required(definition, "definition");
    columnNames = Rows.list(columnNames);
  }

  @Override public Key key() {
    return Key.of(schemaName, viewName);
  }
}
