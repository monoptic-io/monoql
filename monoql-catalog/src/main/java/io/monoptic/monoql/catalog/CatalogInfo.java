/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.catalog;

import java.util.Map;

/** A row of {@code metadata.CATALOGS}: a catalog mounted in a session.
 *
 * <p>{@code CREATE CATALOG c WITH (type = 'jdbc', url = '...')} inserts one.
 * The {@code type} selects the {@link CatalogProvider}; the options are
 * handed to it. */
public record CatalogInfo(String catalogName, String type, String comment,
    Map<String, String> options) implements MetaRow {
  public CatalogInfo {
    Rows.required(catalogName, "catalogName");
    Rows.required(type, "type");
    options = Rows.map(options);
  }

  @Override public Key key() {
    return Key.of(catalogName);
  }
}
