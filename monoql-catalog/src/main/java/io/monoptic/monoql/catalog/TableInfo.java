/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.catalog;

import java.util.List;
import java.util.Map;

/** A row of {@code metadata.TABLES}.
 *
 * <p>The columns travel with the table, so that creating a table is a single
 * atomic insert. They are also visible, one row per column, in
 * {@code metadata.COLUMNS}. */
public record TableInfo(String schemaName, String tableName,
    List<Column> columns, List<String> primaryKey, List<String> partitionKeys,
    String comment, Map<String, String> options) implements MetaRow {
  public TableInfo {
    Rows.required(schemaName, "schemaName");
    Rows.required(tableName, "tableName");
    columns = Rows.list(columns);
    primaryKey = Rows.list(primaryKey);
    partitionKeys = Rows.list(partitionKeys);
    options = Rows.map(options);
  }

  @Override public Key key() {
    return Key.of(schemaName, tableName);
  }

  /** Returns a copy with different columns. */
  public TableInfo withColumns(List<Column> columns) {
    return new TableInfo(schemaName, tableName, columns, primaryKey,
        partitionKeys, comment, options);
  }
}
