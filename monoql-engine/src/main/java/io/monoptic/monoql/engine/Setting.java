/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.engine;

import io.monoptic.monoql.catalog.Key;
import io.monoptic.monoql.catalog.MetaRow;

/** A row of {@code METADATA.SETTINGS}: one piece of session state.
 *
 * <p>{@code USE} and {@code SET} update these rows. The settings are
 * {@link #CATALOG} and {@link #SCHEMA}, the defaults for unqualified names,
 * and {@link #USER}, which cannot be changed. */
public record Setting(String settingName, String settingValue)
    implements MetaRow {
  public static final String CATALOG = "catalog";
  public static final String SCHEMA = "schema";
  /** Who the session acts as; null if nobody, in which case nothing is
   * checked. Fixed when the session is built. */
  public static final String USER = "user";

  @Override public Key key() {
    return Key.of(settingName);
  }
}
