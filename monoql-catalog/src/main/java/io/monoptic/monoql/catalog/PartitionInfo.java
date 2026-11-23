/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.catalog;

import java.util.Map;

/** A row of {@code metadata.PARTITIONS}.
 *
 * @param schemaName Schema of the table
 * @param tableName Table
 * @param spec Partition key values, e.g. {@code {ds=2026-01-01, region=us}}
 * @param options Options, e.g. a location */
public record PartitionInfo(String schemaName, String tableName,
    Map<String, String> spec, Map<String, String> options) implements MetaRow {
  public PartitionInfo {
    Rows.required(schemaName, "schemaName");
    Rows.required(tableName, "tableName");
    spec = Rows.map(spec);
    options = Rows.map(options);
  }

  @Override public Key key() {
    return Key.of(schemaName, tableName, spec);
  }
}
