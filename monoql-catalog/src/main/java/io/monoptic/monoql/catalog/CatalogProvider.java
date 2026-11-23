/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.catalog;

import java.util.Map;

/** Creates {@link Catalog}s of one type. This is the SPI: implementations are
 * discovered with {@link java.util.ServiceLoader}, so dropping a jar with a
 * {@code META-INF/services/io.monoptic.monoql.catalog.CatalogProvider} entry
 * onto the class path makes a new {@code type} available to
 * {@code CREATE CATALOG}. */
public interface CatalogProvider {
  /** The value of the {@code type} option that selects this provider, e.g.
   * {@code memory} or {@code jdbc}. Matched case-insensitively. */
  String type();

  /** Creates a catalog.
   *
   * @param name Name the catalog is mounted under
   * @param options Options of the {@code CREATE CATALOG} statement, without
   *     {@code type} */
  Catalog create(String name, Map<String, String> options);
}
