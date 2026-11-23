/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.catalog.memory;

import io.monoptic.monoql.catalog.Catalog;
import io.monoptic.monoql.catalog.CatalogProvider;

import java.util.Map;

/** Provides {@code type = 'memory'} catalogs. */
public class MemoryCatalogProvider implements CatalogProvider {
  @Override public String type() {
    return "memory";
  }

  @Override public Catalog create(String name, Map<String, String> options) {
    return new MemoryCatalog();
  }
}
