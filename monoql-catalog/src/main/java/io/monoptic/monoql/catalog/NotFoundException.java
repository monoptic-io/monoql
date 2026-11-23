/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.catalog;

/** Thrown when a row, or something a row refers to, does not exist. */
public class NotFoundException extends CatalogException {
  public NotFoundException(String message) {
    super(message);
  }

  public static NotFoundException of(String table, Key key) {
    return new NotFoundException(table + " does not contain " + key);
  }
}
