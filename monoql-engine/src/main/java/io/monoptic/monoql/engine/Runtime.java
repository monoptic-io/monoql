/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.engine;

import org.apache.calcite.DataContext;
import org.apache.calcite.adapter.enumerable.EnumerableInterpretable;
import org.apache.calcite.adapter.enumerable.EnumerableRel;
import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Enumerator;
import org.apache.calcite.linq4j.QueryProvider;
import org.apache.calcite.linq4j.QueryProviderImpl;
import org.apache.calcite.linq4j.Queryable;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.runtime.Bindable;
import org.apache.calcite.schema.SchemaPlus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs an enumerable plan: compiles it, binds it to a {@link DataContext},
 * and collects the rows. This is the whole of the execution path; there is
 * no statement, connection or driver behind it. */
final class Runtime {
  private Runtime() {
  }

  /** Executes a plan and returns its rows, each as an array. */
  static List<Object[]> execute(RelNode physical, SchemaPlus rootSchema,
      JavaTypeFactory typeFactory) {
    if (!(physical instanceof EnumerableRel)) {
      throw new IllegalStateException("Plan is not executable: "
          + physical.getConvention());
    }
    final Map<String, Object> parameters = new HashMap<>();
    final Bindable<?> bindable =
        EnumerableInterpretable.toBindable(parameters, null,
            (EnumerableRel) physical, EnumerableRel.Prefer.ARRAY);
    final Enumerable<?> enumerable =
        bindable.bind(new Context(rootSchema, typeFactory, parameters));
    // A plan with a single column yields bare values, not arrays.
    final boolean scalar = physical.getRowType().getFieldCount() == 1;
    final List<Object[]> rows = new ArrayList<>();
    try (Enumerator<?> enumerator = enumerable.enumerator()) {
      while (enumerator.moveNext()) {
        final Object o = enumerator.current();
        rows.add(scalar ? new Object[] {o} : (Object[]) o);
      }
    }
    return rows;
  }

  /** The context a plan runs in. Time is UTC, and frozen for the duration
   * of the statement. */
  private static final class Context implements DataContext {
    private final SchemaPlus rootSchema;
    private final JavaTypeFactory typeFactory;
    private final Map<String, Object> variables;
    private final QueryProvider queryProvider =
        new QueryProviderImpl() {
          @Override public <T> Enumerator<T> executeQuery(
              Queryable<T> queryable) {
            return queryable.enumerator();
          }
        };

    Context(SchemaPlus rootSchema, JavaTypeFactory typeFactory,
        Map<String, Object> parameters) {
      this.rootSchema = rootSchema;
      this.typeFactory = typeFactory;
      final long now = System.currentTimeMillis();
      final Map<String, Object> map = new HashMap<>(parameters);
      map.put(Variable.UTC_TIMESTAMP.camelName, now);
      map.put(Variable.CURRENT_TIMESTAMP.camelName, now);
      map.put(Variable.LOCAL_TIMESTAMP.camelName, now);
      map.put(Variable.TIME_ZONE.camelName, TimeZone.getTimeZone("UTC"));
      map.put(Variable.LOCALE.camelName, Locale.ROOT);
      map.put(Variable.CANCEL_FLAG.camelName, new AtomicBoolean());
      map.put(Variable.USER.camelName, System.getProperty("user.name"));
      map.put(Variable.SYSTEM_USER.camelName,
          System.getProperty("user.name"));
      this.variables = map;
    }

    @Override public SchemaPlus getRootSchema() {
      return rootSchema;
    }

    @Override public JavaTypeFactory getTypeFactory() {
      return typeFactory;
    }

    @Override public QueryProvider getQueryProvider() {
      return queryProvider;
    }

    @Override public Object get(String name) {
      return variables.get(name);
    }
  }
}
