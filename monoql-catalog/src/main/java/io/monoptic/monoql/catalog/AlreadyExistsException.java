/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.catalog;

/** Thrown by {@link MetaTable#insert} when a row with the same key exists. */
public class AlreadyExistsException extends CatalogException {
  public AlreadyExistsException(String message) {
    super(message);
  }

  public static AlreadyExistsException of(String table, Key key) {
    return new AlreadyExistsException(table + " already contains " + key);
  }
}
