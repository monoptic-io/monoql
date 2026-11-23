/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.catalog;

/** Thrown when a catalog does not support a metadata table, or one operation
 * on it. This is the default behavior of every part of the SPI. */
public class UnsupportedException extends CatalogException {
  public UnsupportedException(String message) {
    super(message);
  }

  /** Creates the exception for an operation on a named metadata table. */
  public static UnsupportedException of(String table, String operation) {
    return new UnsupportedException(
        "This catalog does not support " + operation + " on " + table);
  }
}
