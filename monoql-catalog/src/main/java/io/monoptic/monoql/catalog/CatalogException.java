/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.catalog;

/** Base class for failures raised by a {@link Catalog}. */
public class CatalogException extends RuntimeException {
  public CatalogException(String message) {
    super(message);
  }

  public CatalogException(String message, Throwable cause) {
    super(message, cause);
  }
}
