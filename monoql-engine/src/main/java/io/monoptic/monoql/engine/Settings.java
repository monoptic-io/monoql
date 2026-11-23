/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.engine;

import io.monoptic.monoql.catalog.Catalog;
import io.monoptic.monoql.catalog.CatalogException;
import io.monoptic.monoql.catalog.Key;
import io.monoptic.monoql.catalog.MetaTable;
import io.monoptic.monoql.catalog.NotFoundException;
import io.monoptic.monoql.catalog.UnsupportedException;

import java.util.List;

/** Session state, as {@code METADATA.SETTINGS}. Rows can be updated but not
 * added or removed. Setting the catalog clears the schema. */
final class Settings implements MetaTable<Setting> {
  static final String NAME = "SETTINGS";

  private final CatalogRegistry registry;
  private String catalog;
  private String schema;

  Settings(CatalogRegistry registry) {
    this.registry = registry;
  }

  synchronized String catalog() {
    return catalog;
  }

  synchronized String schema() {
    return schema;
  }

  /** Forgets a current catalog that is no longer mounted. */
  synchronized void validate() {
    if (catalog != null && registry.catalog(catalog) == null) {
      catalog = null;
      schema = null;
    }
  }

  @Override public String name() {
    return NAME;
  }

  @Override public synchronized Iterable<Setting> scan() {
    return List.of(new Setting(Setting.CATALOG, catalog),
        new Setting(Setting.SCHEMA, schema));
  }

  @Override public synchronized void update(Setting before, Setting after) {
    if (!before.key().equals(after.key())) {
      throw new CatalogException("Settings cannot be renamed");
    }
    final String value = after.settingValue();
    switch (String.valueOf(before.settingName())) {
    case Setting.CATALOG:
      if (value != null && registry.catalog(value) == null) {
        throw new NotFoundException("Catalog " + value + " not found");
      }
      if (!java.util.Objects.equals(catalog, value)) {
        catalog = value;
        schema = null;
      }
      break;
    case Setting.SCHEMA:
      if (value != null) {
        if (catalog == null) {
          throw new CatalogException(
              "Cannot set the schema: there is no current catalog");
        }
        final Catalog c = registry.catalog(catalog);
        boolean found;
        try {
          found = c.schemas().get(Key.of(value)).isPresent();
        } catch (UnsupportedException e) {
          found = false;
        }
        if (!found) {
          throw new NotFoundException("Schema " + value
              + " not found in catalog " + catalog);
        }
      }
      schema = value;
      break;
    default:
      throw NotFoundException.of(NAME, before.key());
    }
  }
}
