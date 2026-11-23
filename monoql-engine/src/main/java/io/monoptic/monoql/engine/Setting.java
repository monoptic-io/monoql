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
 * {@link #CATALOG} and {@link #SCHEMA}, the defaults for unqualified names. */
public record Setting(String settingName, String settingValue)
    implements MetaRow {
  public static final String CATALOG = "catalog";
  public static final String SCHEMA = "schema";

  @Override public Key key() {
    return Key.of(settingName);
  }
}
