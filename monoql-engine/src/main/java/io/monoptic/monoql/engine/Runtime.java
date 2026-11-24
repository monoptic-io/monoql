/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.engine;

import io.monoptic.monoql.MonoqlException;
import io.monoptic.monoql.catalog.DataTypes;

import org.apache.calcite.DataContext;
import org.apache.calcite.adapter.enumerable.EnumerableInterpretable;
import org.apache.calcite.adapter.enumerable.EnumerableRel;
import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.linq4j.Enumerator;
import org.apache.calcite.linq4j.QueryProvider;
import org.apache.calcite.linq4j.QueryProviderImpl;
import org.apache.calcite.linq4j.Queryable;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.runtime.Bindable;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.type.SqlTypeName;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs an enumerable plan: compiles it, binds it to a {@link DataContext},
 * and reads the rows. This is the whole of the execution path; there is
 * no statement, connection or driver behind it. */
final class Runtime {
  private Runtime() {
  }

  /** Starts a plan and returns a cursor over its rows, each as an array.
   * The caller closes it.
   *
   * @param cancel Set by another thread to stop the plan. The cursor checks
   *     it before each row, and it is passed to the plan as
   *     {@link DataContext.Variable#CANCEL_FLAG} for table scans to check
   * @param user Who the session acts as, or null
   * @param bound The values of the statement's dynamic parameters, in the
   *     engine's internal representation; see {@link #bind} */
  static Enumerator<Object[]> open(RelNode physical, SchemaPlus rootSchema,
      JavaTypeFactory typeFactory, AtomicBoolean cancel, String user,
      List<Object> bound) {
    if (!(physical instanceof EnumerableRel)) {
      throw new IllegalStateException("Plan is not executable: "
          + physical.getConvention());
    }
    final Map<String, Object> parameters = new HashMap<>();
    final Bindable<?> bindable =
        EnumerableInterpretable.toBindable(parameters, null,
            (EnumerableRel) physical, EnumerableRel.Prefer.ARRAY);
    final Enumerator<?> enumerator =
        bindable.bind(
            new Context(rootSchema, typeFactory, parameters, cancel, user,
                bound))
            .enumerator();
    // A plan with a single column yields bare values, not arrays.
    final boolean scalar = physical.getRowType().getFieldCount() == 1;
    return new Enumerator<>() {
      @Override public Object[] current() {
        final Object o = enumerator.current();
        return scalar ? new Object[] {o} : (Object[]) o;
      }

      @Override public boolean moveNext() {
        if (cancel.get()) {
          throw new CancellationException("Statement cancelled");
        }
        return enumerator.moveNext();
      }

      @Override public void reset() {
        enumerator.reset();
      }

      @Override public void close() {
        enumerator.close();
      }
    };
  }

  /** Checks the values given for a statement's dynamic parameters, and
   * converts them to the engine's internal representation: the reverse of
   * what {@link io.monoptic.monoql.Result} does to the values it returns.
   *
   * @param parameterType The parameters' types, as fields of a row type */
  static List<Object> bind(RelDataType parameterType, Object[] values) {
    final List<RelDataTypeField> fields = parameterType.getFieldList();
    if (fields.size() != values.length) {
      throw new MonoqlException("The statement has " + fields.size()
          + (fields.size() == 1 ? " parameter" : " parameters") + ", but "
          + values.length + (values.length == 1 ? " value was" : " values were")
          + " given");
    }
    final List<Object> bound = new ArrayList<>();
    for (int i = 0; i < values.length; i++) {
      final RelDataType type = fields.get(i).getType();
      try {
        bound.add(internal(type.getSqlTypeName(), values[i]));
      } catch (ClassCastException | NumberFormatException e) {
        throw new MonoqlException("Parameter " + (i + 1) + " is "
            + DataTypes.format(type) + "; it cannot be given a "
            + values[i].getClass().getSimpleName(), e);
      }
    }
    return bound;
  }

  private static Object internal(SqlTypeName type, Object value) {
    if (value == null) {
      return null;
    }
    switch (type) {
    case TINYINT:
      return ((Number) value).byteValue();
    case SMALLINT:
      return ((Number) value).shortValue();
    case INTEGER:
      return ((Number) value).intValue();
    case BIGINT:
      return ((Number) value).longValue();
    case REAL:
      return ((Number) value).floatValue();
    case FLOAT:
    case DOUBLE:
      return ((Number) value).doubleValue();
    case DECIMAL:
      return value instanceof BigDecimal ? value
          : new BigDecimal(((Number) value).toString());
    case BOOLEAN:
      return (Boolean) value;
    case CHAR:
    case VARCHAR:
      return (String) value;
    case DATE:
      return (int) ((LocalDate) value).toEpochDay();
    case TIME:
      return (int) (((LocalTime) value).toNanoOfDay() / 1_000_000L);
    case TIMESTAMP:
      return ((LocalDateTime) value).toInstant(ZoneOffset.UTC).toEpochMilli();
    case TIMESTAMP_WITH_LOCAL_TIME_ZONE:
      return ((Instant) value).toEpochMilli();
    default:
      return value;
    }
  }

  /** Executes a plan and returns all of its rows. */
  static List<Object[]> execute(RelNode physical, SchemaPlus rootSchema,
      JavaTypeFactory typeFactory, AtomicBoolean cancel, String user,
      List<Object> bound) {
    final List<Object[]> rows = new ArrayList<>();
    try (Enumerator<Object[]> enumerator =
        open(physical, rootSchema, typeFactory, cancel, user, bound)) {
      while (enumerator.moveNext()) {
        rows.add(enumerator.current());
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
        Map<String, Object> parameters, AtomicBoolean cancel, String user,
        List<Object> bound) {
      this.rootSchema = rootSchema;
      this.typeFactory = typeFactory;
      final long now = System.currentTimeMillis();
      final Map<String, Object> map = new HashMap<>(parameters);
      // A plan reads the value of its nth dynamic parameter as "?n".
      for (int i = 0; i < bound.size(); i++) {
        map.put("?" + i, bound.get(i));
      }
      map.put(Variable.UTC_TIMESTAMP.camelName, now);
      map.put(Variable.CURRENT_TIMESTAMP.camelName, now);
      map.put(Variable.LOCAL_TIMESTAMP.camelName, now);
      map.put(Variable.TIME_ZONE.camelName, TimeZone.getTimeZone("UTC"));
      map.put(Variable.LOCALE.camelName, Locale.ROOT);
      map.put(Variable.CANCEL_FLAG.camelName, cancel);
      // USER and CURRENT_USER are who the session acts as; SYSTEM_USER is
      // who runs the process, which is also who a session with no user of
      // its own is taken to be.
      final String systemUser = System.getProperty("user.name");
      map.put(Variable.USER.camelName, user != null ? user : systemUser);
      map.put(Variable.SYSTEM_USER.camelName, systemUser);
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
