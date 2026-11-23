/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.catalog;

/** A column of a table. Appears nested in {@link TableInfo#columns()}, and
 * flattened in {@code metadata.COLUMNS}.
 *
 * @param columnName Name
 * @param dataType SQL type, e.g. {@code VARCHAR(20)} or {@code INTEGER ARRAY};
 *     see {@link DataTypes}
 * @param nullable Whether the column admits nulls
 * @param comment Comment, or null */
public record Column(String columnName, String dataType, boolean nullable,
    String comment) {
  public Column {
    Rows.required(columnName, "columnName");
    Rows.required(dataType, "dataType");
  }
}
