/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql;

/** A statement could not be parsed, validated or planned. Failures raised by
 * a catalog are {@link io.monoptic.monoql.catalog.CatalogException}s
 * instead. */
public class MonoqlException extends RuntimeException {
  public MonoqlException(String message, Throwable cause) {
    super(message, cause);
  }
}
