/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.engine;

import io.monoptic.monoql.catalog.Key;
import io.monoptic.monoql.catalog.MetaRow;

/** A row of {@code METADATA.COLUMNS}: one column of one table.
 *
 * <p>This table is derived from {@code METADATA.TABLES}, whose rows carry
 * their columns, so catalogs get it for free.
 *
 * @param schemaName Schema of the table
 * @param tableName Table
 * @param columnName Column
 * @param ordinalPosition Position, from 1. On insert, null means last
 * @param dataType SQL type
 * @param nullable Whether the column admits nulls
 * @param comment Comment, or null */
public record ColumnInfo(String schemaName, String tableName,
    String columnName, Integer ordinalPosition, String dataType, Boolean nullable,
    String comment) implements MetaRow {
  @Override public Key key() {
    return Key.of(schemaName, tableName, columnName);
  }
}
