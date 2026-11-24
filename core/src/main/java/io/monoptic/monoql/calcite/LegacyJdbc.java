/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.calcite;

import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.jdbc.CalciteConnection;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.schema.Schema;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Iterator;
import java.util.ServiceLoader;

/** The one place where Calcite's core may reach for a {@code jdbc:calcite:}
 * connection.
 *
 * <p>The driver is not part of core any more; it lives in the
 * {@code legacy-jdbc} module, which exists to run upstream's tests. A few
 * things still ask for a connection: a statement prepared through
 * {@code Frameworks} that wants the connection it runs on, and the old
 * "metadata" schema. They ask here. If {@code legacy-jdbc} is on the class
 * path they get it; if not, they fail with an explanation.
 *
 * <p>Nothing in monoql's own path (the catalog API, the engine, the shell)
 * comes through this class. */
public final class LegacyJdbc {
  private LegacyJdbc() {
  }

  /** Implemented by the {@code legacy-jdbc} module and found with
   * {@link ServiceLoader}. */
  public interface Provider {
    /** Creates a connection.
     *
     * @param rootSchema Root schema, or null for a new, empty one
     * @param typeFactory Type factory, or null for the default */
    CalciteConnection connect(@Nullable CalciteSchema rootSchema,
        @Nullable JavaTypeFactory typeFactory);

    /** Returns the old "metadata" schema, whose tables are backed by JDBC
     * {@code DatabaseMetaData}. */
    Schema metadataSchema();
  }

  /** Loads the provider once, on first use. */
  private static final class Holder {
    static final @Nullable Provider PROVIDER = load();

    private static @Nullable Provider load() {
      final Iterator<Provider> providers =
          ServiceLoader.load(Provider.class,
              LegacyJdbc.class.getClassLoader()).iterator();
      return providers.hasNext() ? providers.next() : null;
    }
  }

  /** Whether the legacy driver is on the class path. */
  public static boolean available() {
    return Holder.PROVIDER != null;
  }

  private static Provider provider(String feature) {
    final Provider provider = Holder.PROVIDER;
    if (provider == null) {
      throw new UnsupportedOperationException(feature
          + " requires Calcite's legacy JDBC driver, which monoql does not"
          + " ship. To use it anyway, add the legacy-jdbc module to the"
          + " class path.");
    }
    return provider;
  }

  /** Creates a connection over a schema; see {@link Provider#connect}. */
  public static CalciteConnection connect(String feature,
      @Nullable CalciteSchema rootSchema,
      @Nullable JavaTypeFactory typeFactory) {
    return provider(feature).connect(rootSchema, typeFactory);
  }

  /** Returns the old "metadata" schema, or null if the legacy driver is not
   * on the class path. */
  public static @Nullable Schema metadataSchema() {
    final Provider provider = Holder.PROVIDER;
    return provider == null ? null : provider.metadataSchema();
  }
}
