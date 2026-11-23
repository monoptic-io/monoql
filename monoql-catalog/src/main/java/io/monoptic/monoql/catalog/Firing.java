/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.catalog;

import java.time.Instant;
import java.util.Map;

/** A row of {@code metadata.FIRINGS}: a request to run a trigger now.
 *
 * <p>{@code FIRE TRIGGER t} inserts one. A backend that keeps history can
 * return past firings from {@link MetaTable#scan}; one that does not need
 * only implement {@link MetaTable#insert}.
 *
 * @param schemaName Schema of the trigger
 * @param triggerName Trigger
 * @param firedAt When the firing was requested
 * @param options Parameters of this firing */
public record Firing(String schemaName, String triggerName, Instant firedAt,
    Map<String, String> options) implements MetaRow {
  public Firing {
    Rows.required(schemaName, "schemaName");
    Rows.required(triggerName, "triggerName");
    options = Rows.map(options);
  }

  @Override public Key key() {
    return Key.of(schemaName, triggerName, firedAt);
  }
}
