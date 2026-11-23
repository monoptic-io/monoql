/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql;

import io.monoptic.monoql.catalog.Catalog;
import io.monoptic.monoql.catalog.SchemaInfo;
import io.monoptic.monoql.catalog.memory.MemoryCatalog;
import io.monoptic.monoql.engine.Engine;
import io.monoptic.monoql.engine.Lowered;

import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.tools.FrameworkConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** A conversation with monoql: a set of mounted catalogs, a current catalog
 * and schema, and the means to run statements against them.
 *
 * <p>A session is cheap, holds no connection, and is not thread-safe. */
public final class Session implements AutoCloseable {
  private final Engine engine;

  Session(Monoql.Builder builder) {
    this.engine = new Engine(builder.frameworkConfig(), builder.providers);
    String catalog = builder.defaultCatalog;
    String schema = builder.defaultSchema;
    if (builder.catalogs.isEmpty()) {
      final MemoryCatalog memory = new MemoryCatalog();
      memory.schemas().insert(
          new SchemaInfo(Monoql.DEFAULT_SCHEMA, null, Map.of()));
      engine.mount(Monoql.DEFAULT_CATALOG, memory);
      if (catalog == null) {
        catalog = Monoql.DEFAULT_CATALOG;
        schema = Monoql.DEFAULT_SCHEMA;
      }
    }
    for (Map.Entry<String, Catalog> e : builder.catalogs.entrySet()) {
      engine.mount(e.getKey(), e.getValue());
      if (catalog == null) {
        catalog = e.getKey();
      }
    }
    engine.use(catalog, schema);
  }

  /** Executes one statement. */
  public Result execute(String sql) {
    return engine.execute(sql);
  }

  /** Executes statements separated by semicolons, stopping at the first
   * failure; returns one result per statement. */
  public List<Result> executeScript(String sql) {
    final List<Result> results = new ArrayList<>();
    engine.executeScript(sql, results::add);
    return results;
  }

  /** Executes statements separated by semicolons, handing each result to
   * {@code sink} as soon as it is available, and stopping at the first
   * failure. */
  public void executeScript(String sql, Consumer<Result> sink) {
    engine.executeScript(sql, sink);
  }

  /** Returns what a statement means, as DML and queries against the
   * {@code METADATA} schema, without running it. A statement that is
   * already DML or a query is returned as it is. */
  public Lowered lower(String sql) {
    return engine.lower(sql);
  }

  /** Returns the plan of a query or DML statement. */
  public String explain(String sql) {
    return engine.explain(sql);
  }

  /** Current catalog, or null. */
  public String currentCatalog() {
    return engine.currentCatalog();
  }

  /** Current schema, or null. */
  public String currentSchema() {
    return engine.currentSchema();
  }

  /** The root of the schema tree: {@code METADATA} and one schema per
   * catalog. Useful with {@link org.apache.calcite.tools.RelBuilder} and
   * other Calcite tools. */
  public SchemaPlus rootSchema() {
    return engine.rootSchema();
  }

  /** The Calcite configuration this session plans with. */
  public FrameworkConfig frameworkConfig() {
    return engine.config();
  }

  @Override public void close() {
    engine.close();
  }
}
