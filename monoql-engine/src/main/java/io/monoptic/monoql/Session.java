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

import java.util.Map;
import java.util.function.Consumer;

/** A conversation with monoql: a set of mounted catalogs, a current catalog
 * and schema, and the means to run statements against them.
 *
 * <p>A session is cheap, holds no connection, and is not thread-safe,
 * except for {@link #cancel}. */
public final class Session implements AutoCloseable {
  private final Engine engine;

  Session(Monoql.Builder builder) {
    this.engine =
        new Engine(builder.frameworkConfig(), builder.providers,
            builder.identity);
    String catalog = builder.defaultCatalog;
    String schema = builder.defaultSchema;
    if (builder.catalogs.isEmpty() && !builder.bare) {
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

  private Session(Engine engine) {
    this.engine = engine;
  }

  /** Returns a session over the same catalogs, acting as someone else, and
   * starting in the same catalog and schema. Each session has its own
   * current catalog and schema; catalogs mounted or dropped through one
   * are seen by the other. Closing the new session leaves the catalogs
   * open; closing this one closes them.
   *
   * <p>This is how one application serves many users from one set of
   * catalogs. Any session can do it, whoever it acts as: whoever holds a
   * session is already trusted to say who it speaks for.
   *
   * @param identity Who to act as; null for nobody, which is unchecked */
  public Session as(Identity identity) {
    return new Session(engine.as(identity));
  }

  /** Who this session acts as; null if nobody. */
  public Identity identity() {
    return engine.identity();
  }

  /** Executes one statement. The rows of a query are read as the result
   * is; close the result unless you read them all.
   *
   * @param parameters One value for each {@code ?} in the statement, which
   *     must then be a query or {@code INSERT}, {@code UPDATE} or
   *     {@code DELETE}; see {@link Prepared#execute} */
  public Result execute(String sql, Object... parameters) {
    return engine.execute(sql, parameters);
  }

  /** Checks a statement without running it, and returns what it will
   * return and what parameters it takes. */
  public Prepared prepare(String sql) {
    return engine.prepare(sql);
  }

  /** Executes statements separated by semicolons, handing each result to
   * {@code sink} as soon as it is available, and stopping at the first
   * failure. A result is closed when the sink returns, so the sink must
   * read the rows it wants. */
  public void executeScript(String sql, Consumer<Result> sink) {
    engine.executeScript(sql, sink);
  }

  /** Asks the statement that is running, or whose rows are being read, to
   * stop; it then fails with
   * {@link java.util.concurrent.CancellationException}. This is the one
   * method that may be called from another thread.
   *
   * <p>The engine notices between rows of the result, and the in-memory
   * catalog between rows of a scan. A statement waiting on another system
   * stops when that system next answers. */
  public void cancel() {
    engine.cancel();
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

  /** Sets the current catalog and schema, as {@code USE} does, but from
   * names as they are stored: nothing is folded to upper case, and nothing
   * needs quoting. Either may be null, for none; a schema needs a catalog.
   *
   * <p>With {@link #as}, {@link #currentCatalog} and
   * {@link #currentSchema}, this is how a server keeps no sessions: it
   * hands a client its catalog and schema, and makes a session of them
   * again for each request.
   *
   * @throws io.monoptic.monoql.catalog.NotFoundException if the catalog is
   *     not mounted, or has no such schema; the session is left as it was */
  public void use(String catalog, String schema) {
    engine.use(catalog, schema);
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
