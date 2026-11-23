/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.catalog;

import java.util.List;
import java.util.Map;

/** A row of {@code metadata.TRIGGERS}.
 *
 * <p>Deliberately broad: it covers classic row triggers (timing, events and a
 * table), scheduled tasks (a schedule and no table), and table-change hooks (a
 * table and no events).
 *
 * @param schemaName Schema
 * @param triggerName Name
 * @param tableSchema Schema of the table the trigger is on, or null
 * @param tableName Table the trigger is on, or null
 * @param timing {@code BEFORE} or {@code AFTER}, or null
 * @param events Subset of {@code INSERT}, {@code UPDATE}, {@code DELETE}
 * @param schedule Schedule (typically a cron expression), or null
 * @param action What to run: a SQL statement, or anything else the backend
 *     understands
 * @param paused Whether the trigger is paused
 * @param comment Comment, or null
 * @param options Options */
public record TriggerInfo(String schemaName, String triggerName,
    String tableSchema, String tableName, String timing, List<String> events,
    String schedule, String action, boolean paused, String comment,
    Map<String, String> options) implements MetaRow {
  public TriggerInfo {
    Rows.required(schemaName, "schemaName");
    Rows.required(triggerName, "triggerName");
    Rows.required(action, "action");
    events = Rows.list(events);
    options = Rows.map(options);
  }

  @Override public Key key() {
    return Key.of(schemaName, triggerName);
  }
}
