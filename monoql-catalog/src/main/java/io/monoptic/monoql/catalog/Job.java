/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.catalog;

import java.time.Instant;
import java.util.Map;

/** A statement handed to something else to run: another engine, a worker,
 * whatever stands behind the catalog. Inserting the row submits the job,
 * and deleting it cancels the job.
 *
 * <p>Whoever submits a job says what to run; whatever runs it says how it
 * went, in {@link #state}, {@link #startedAt}, {@link #finishedAt} and
 * {@link #error}, which are null until then.
 *
 * @param jobName A name, which need not be unique: firing a trigger
 *     twice submits two jobs of the trigger's name
 * @param submittedAt When it was submitted, which tells such jobs apart
 * @param statement What to run
 * @param options Parameters of this run
 * @param state How far it has got, in the words of whatever runs it; null
 *     if nothing has picked it up
 * @param error Why it failed, if it did */
public record Job(String schemaName, String jobName, Instant submittedAt,
    String statement, Map<String, String> options, String state,
    Instant startedAt, Instant finishedAt, String error) implements MetaRow {
  public Job {
    Rows.required(schemaName, "schemaName");
    Rows.required(jobName, "jobName");
    Rows.required(statement, "statement");
    options = Rows.map(options);
  }

  @Override public Key key() {
    return Key.of(schemaName, jobName, submittedAt);
  }
}
