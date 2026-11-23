/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.catalog;

/** Thrown when the user of a session lacks a privilege that a statement
 * needs. */
public class AccessDeniedException extends CatalogException {
  public AccessDeniedException(String message) {
    super(message);
  }
}
