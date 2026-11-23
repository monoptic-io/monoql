/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.sql;

/** Kinds of object that statements can name. */
public enum ObjectKind {
  CATALOG("CATALOG"),
  SCHEMA("SCHEMA"),
  TABLE("TABLE"),
  VIEW("VIEW"),
  MATERIALIZED_VIEW("MATERIALIZED VIEW"),
  FUNCTION("FUNCTION"),
  TRIGGER("TRIGGER"),
  ROLE("ROLE"),
  USER("USER"),
  COLUMN("COLUMN");

  /** How the kind is written in SQL, and stored in
   * {@code metadata.GRANTS.OBJECT_TYPE}. */
  public final String sql;

  ObjectKind(String sql) {
    this.sql = sql;
  }
}
