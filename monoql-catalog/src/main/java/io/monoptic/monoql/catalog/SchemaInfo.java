/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.catalog;

import java.util.Map;

/** A row of {@code metadata.SCHEMAS}: a namespace within a catalog. */
public record SchemaInfo(String schemaName, String comment,
    Map<String, String> options) implements MetaRow {
  public SchemaInfo {
    Rows.required(schemaName, "schemaName");
    options = Rows.map(options);
  }

  @Override public Key key() {
    return Key.of(schemaName);
  }
}
