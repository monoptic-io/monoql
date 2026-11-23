/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.jdbc;

import io.monoptic.monoql.catalog.Catalog;
import io.monoptic.monoql.catalog.CatalogException;
import io.monoptic.monoql.catalog.CatalogProvider;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Provides {@code type = 'jdbc'} catalogs.
 *
 * <p>Options:
 *
 * <ul>
 * <li>{@code url} (required): the JDBC URL;
 * <li>{@code driver}: the driver class, if the URL alone does not find it;
 * <li>{@code user}, {@code password};
 * <li>{@code catalog}: the database catalog to expose, for databases that
 *     have several; by default, the connection's.
 * </ul> */
public class JdbcCatalogProvider implements CatalogProvider {
  private static final Set<String> OPTIONS =
      Set.of("url", "driver", "user", "password", "catalog");

  @Override public String type() {
    return "jdbc";
  }

  @Override public Catalog create(String name, Map<String, String> options) {
    final Set<String> unknown = new TreeSet<>(options.keySet());
    unknown.removeAll(OPTIONS);
    if (!unknown.isEmpty()) {
      throw new CatalogException("Unknown options " + unknown
          + " for a jdbc catalog; expected " + new TreeSet<>(OPTIONS));
    }
    final String url = options.get("url");
    if (url == null) {
      throw new CatalogException("A jdbc catalog requires a url");
    }
    return new JdbcCatalog(url, options.get("driver"), options.get("user"),
        options.get("password"), options.get("catalog"));
  }
}
