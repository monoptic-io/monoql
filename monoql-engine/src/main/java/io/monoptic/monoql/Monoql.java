/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql;

import io.monoptic.monoql.catalog.Catalog;
import io.monoptic.monoql.catalog.CatalogProvider;
import io.monoptic.monoql.sql.parser.MonoqlParserImpl;

import org.apache.calcite.rel.type.RelDataTypeSystem;
import org.apache.calcite.rel.type.RelDataTypeSystemImpl;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.validate.SqlConformance;
import org.apache.calcite.sql.validate.SqlConformanceEnum;
import org.apache.calcite.sql.validate.SqlDelegatingConformance;
import org.apache.calcite.tools.FrameworkConfig;
import org.apache.calcite.tools.Frameworks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/** Entry point: builds {@link Session}s.
 *
 * <pre>{@code
 * try (Session session = Monoql.builder().build()) {
 *   session.execute("CREATE TABLE t (id INT, name VARCHAR)");
 *   session.execute("GRANT SELECT ON t TO ROLE analyst");
 *   Result r = session.execute("SELECT * FROM metadata.grants");
 * }
 * }</pre> */
public final class Monoql {
  /** Name of the in-memory catalog a session has if none is given. */
  public static final String DEFAULT_CATALOG = "MEMORY";

  /** Name of the schema created in the default catalog. */
  public static final String DEFAULT_SCHEMA = "MAIN";

  private Monoql() {
  }

  public static Builder builder() {
    return new Builder();
  }

  /** The SQL dialect: Calcite's default, except that character literals of
   * different lengths in the same column are not blank-padded to match. */
  public static final SqlConformance CONFORMANCE =
      new SqlDelegatingConformance(SqlConformanceEnum.DEFAULT) {
        @Override public boolean shouldConvertRaggedUnionTypesToVarying() {
          return true;
        }
      };

  /** The type system that goes with {@link #CONFORMANCE}. */
  public static final RelDataTypeSystem TYPE_SYSTEM =
      new RelDataTypeSystemImpl() {
        @Override public boolean shouldConvertRaggedUnionTypesToVarying() {
          return true;
        }
      };

  /** The parser configuration: monoql's grammar, standard SQL casing
   * (unquoted identifiers fold to upper case, double quotes preserve). */
  public static SqlParser.Config parserConfig() {
    return SqlParser.config()
        .withParserFactory(MonoqlParserImpl.FACTORY)
        .withConformance(CONFORMANCE);
  }

  /** Builds a {@link Session}. */
  public static final class Builder {
    final Map<String, Catalog> catalogs = new LinkedHashMap<>();
    final List<CatalogProvider> providers = new ArrayList<>();
    UnaryOperator<Frameworks.ConfigBuilder> configurer = b -> b;
    String defaultCatalog;
    String defaultSchema;
    Identity identity;
    boolean bare;

    private Builder() {
    }

    /** Mounts a catalog. If no catalog is mounted, the session gets an
     * in-memory one called {@link #DEFAULT_CATALOG}, containing a schema
     * called {@link #DEFAULT_SCHEMA}. */
    public Builder catalog(String name, Catalog catalog) {
      catalogs.put(name, catalog);
      return this;
    }

    /** Builds a session with no catalogs at all, if none is mounted,
     * rather than the in-memory one it would otherwise get: for a session
     * whose catalogs are to come from {@code CREATE CATALOG}. It has no
     * current catalog until it is given one. */
    public Builder bare() {
      this.bare = true;
      return this;
    }

    /** Makes a catalog type available to {@code CREATE CATALOG}, in addition
     * to those found on the class path. */
    public Builder provider(CatalogProvider provider) {
      providers.add(provider);
      return this;
    }

    /** Sets the initial current catalog and, optionally, schema. By default
     * the first catalog mounted is current. */
    public Builder use(String catalog, String schema) {
      this.defaultCatalog = catalog;
      this.defaultSchema = schema;
      return this;
    }

    /** Sets who the session acts as. Every statement is then checked
     * against the {@code GRANTS} of the catalogs it touches: reading a
     * table takes {@code SELECT} on it, and so on (see docs/sql.md).
     *
     * <p>By default a session acts as nobody, and nothing is checked. That
     * is how grants are set up in the first place. To serve several users
     * from the same catalogs, build one session and derive the others
     * with {@link Session#as}. */
    public Builder identity(Identity identity) {
      this.identity = identity;
      return this;
    }

    /** Customizes the Calcite {@link FrameworkConfig} the session plans
     * with: operator table, programs, trait definitions, cost factory, and
     * so on. This is the extension point for everything planner-related. */
    public Builder configure(
        UnaryOperator<Frameworks.ConfigBuilder> configurer) {
      final UnaryOperator<Frameworks.ConfigBuilder> previous =
          this.configurer;
      this.configurer = b -> configurer.apply(previous.apply(b));
      return this;
    }

    FrameworkConfig frameworkConfig() {
      return configurer.apply(
          Frameworks.newConfigBuilder()
              .parserConfig(parserConfig())
              .typeSystem(TYPE_SYSTEM))
          .build();
    }

    public Session build() {
      return new Session(this);
    }
  }
}
