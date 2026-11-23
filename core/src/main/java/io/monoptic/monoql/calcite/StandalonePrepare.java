/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.calcite;

import org.apache.calcite.DataContext;
import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.avatica.Meta;
import org.apache.calcite.config.CalciteConnectionConfig;
import org.apache.calcite.config.CalciteConnectionConfigImpl;
import org.apache.calcite.jdbc.CalciteConnection;
import org.apache.calcite.jdbc.CalcitePrepare;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.calcite.linq4j.Enumerator;
import org.apache.calcite.linq4j.QueryProvider;
import org.apache.calcite.linq4j.QueryProviderImpl;
import org.apache.calcite.linq4j.Queryable;
import org.apache.calcite.prepare.CalcitePrepareImpl;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.server.CalciteServerStatement;
import org.apache.calcite.tools.FrameworkConfig;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.RelRunner;

import com.google.common.collect.ImmutableList;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;

/** Sets up a planner, cluster and catalog reader from a
 * {@link FrameworkConfig}, with no JDBC connection behind them.
 *
 * <p>This is what {@link Frameworks#withPrepare} and
 * {@link Frameworks#withPlanner} now run on, and so what
 * {@code RelBuilder.create} runs on. Upstream opens a {@code jdbc:calcite:}
 * connection for the purpose, creates a statement, and asks the statement
 * for its prepare context; here the context is built directly. */
public final class StandalonePrepare {
  private StandalonePrepare() {
  }

  /** Runs an action against a freshly prepared planning environment. */
  public static <R> R perform(FrameworkConfig config,
      Frameworks.BasePrepareAction<R> action) {
    return new CalcitePrepareImpl()
        .perform(new Statement(new Context(config)), config, action);
  }

  /** A prepare context that belongs to no connection. */
  private static final class Context implements CalcitePrepare.Context {
    private final JavaTypeFactory typeFactory;
    private final CalciteSchema rootSchema;
    private final CalciteConnectionConfig config;
    private final DataContext dataContext;

    Context(FrameworkConfig frameworkConfig) {
      this.typeFactory =
          new JavaTypeFactoryImpl(frameworkConfig.getTypeSystem());
      this.rootSchema = CalciteSchema.createRootSchema(true);
      this.config = new CalciteConnectionConfigImpl(new Properties());
      this.dataContext = new StandaloneDataContext(rootSchema, typeFactory);
    }

    @Override public JavaTypeFactory getTypeFactory() {
      return typeFactory;
    }

    @Override public CalciteSchema getRootSchema() {
      return rootSchema;
    }

    @Override public CalciteSchema getMutableRootSchema() {
      return rootSchema;
    }

    @Override public List<String> getDefaultSchemaPath() {
      return ImmutableList.of();
    }

    @Override public CalciteConnectionConfig config() {
      return config;
    }

    @Override public CalcitePrepare.SparkHandler spark() {
      return CalcitePrepare.Dummy.getSparkHandler(false);
    }

    @Override public DataContext getDataContext() {
      return dataContext;
    }

    @Override public @Nullable List<String> getObjectPath() {
      return null;
    }

    @Override public RelRunner getRelRunner() {
      throw new UnsupportedOperationException(
          "There is no connection to run a relational expression on");
    }
  }

  /** The statement handed to prepare actions. It exists only to carry the
   * context; it has no connection. */
  private static final class Statement implements CalciteServerStatement {
    private final Context context;
    private Meta.@Nullable Signature signature;
    private @Nullable Iterator<Object> resultSet;
    private @Nullable CalciteConnection connection;

    Statement(Context context) {
      this.context = context;
    }

    @Override public CalcitePrepare.Context createPrepareContext() {
      return context;
    }

    /** Planning does not need a connection, but the interface promises
     * one, and some callers (upstream's test fixtures among them) ask. They
     * get one if the legacy driver is on the class path. */
    @Override public CalciteConnection getConnection() {
      CalciteConnection c = connection;
      if (c == null) {
        c = LegacyJdbc.connect("CalciteServerStatement.getConnection",
            context.getRootSchema(), context.getTypeFactory());
        connection = c;
      }
      return c;
    }

    @Override public void setSignature(Meta.Signature signature) {
      this.signature = signature;
    }

    @Override public Meta.@Nullable Signature getSignature() {
      return signature;
    }

    @Override public @Nullable Iterator<Object> getResultSet() {
      return resultSet;
    }

    @Override public void setResultSet(Iterator<Object> resultSet) {
      this.resultSet = resultSet;
    }
  }

  /** The context expressions are reduced in during planning. */
  private static final class StandaloneDataContext implements DataContext {
    private final CalciteSchema rootSchema;
    private final JavaTypeFactory typeFactory;
    private final Map<String, Object> variables = new HashMap<>();
    private final QueryProvider queryProvider =
        new QueryProviderImpl() {
          @Override public <T> Enumerator<T> executeQuery(
              Queryable<T> queryable) {
            return queryable.enumerator();
          }
        };

    StandaloneDataContext(CalciteSchema rootSchema,
        JavaTypeFactory typeFactory) {
      this.rootSchema = rootSchema;
      this.typeFactory = typeFactory;
      final long now = System.currentTimeMillis();
      final TimeZone timeZone = TimeZone.getDefault();
      final long local = now + timeZone.getOffset(now);
      variables.put(Variable.UTC_TIMESTAMP.camelName, now);
      variables.put(Variable.CURRENT_TIMESTAMP.camelName, local);
      variables.put(Variable.LOCAL_TIMESTAMP.camelName, local);
      variables.put(Variable.TIME_ZONE.camelName, timeZone);
      variables.put(Variable.LOCALE.camelName, Locale.ROOT);
      variables.put(Variable.CANCEL_FLAG.camelName, new AtomicBoolean());
    }

    @Override public SchemaPlus getRootSchema() {
      return rootSchema.plus();
    }

    @Override public JavaTypeFactory getTypeFactory() {
      return typeFactory;
    }

    @Override public QueryProvider getQueryProvider() {
      return queryProvider;
    }

    @Override public @Nullable Object get(String name) {
      return variables.get(name);
    }
  }
}
