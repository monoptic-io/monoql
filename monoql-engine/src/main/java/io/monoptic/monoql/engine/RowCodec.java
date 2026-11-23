/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.engine;

import io.monoptic.monoql.catalog.Column;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.sql.type.SqlTypeName;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Converts between a row record of the catalog API and the {@code Object[]}
 * rows that the engine computes with, and derives the SQL row type.
 *
 * <p>The mapping is by convention. Record components become columns, in
 * order, named in upper snake case ({@code schemaName} is
 * {@code SCHEMA_NAME}); and
 *
 * <ul>
 * <li>{@code String} is {@code VARCHAR};
 * <li>{@code boolean} is {@code BOOLEAN};
 * <li>{@code Integer} is {@code INTEGER};
 * <li>{@link Instant} is {@code TIMESTAMP(3)}, in UTC;
 * <li>{@code List<String>} is {@code VARCHAR ARRAY};
 * <li>{@code Map<String, String>} is {@code MAP<VARCHAR, VARCHAR>};
 * <li>{@code List<Column>} is {@code VARCHAR ARRAY}, each element a column
 *     declaration as in {@code CREATE TABLE}; see {@link ColumnText}.
 * </ul>
 *
 * <p>Every column is nullable. On the way in, null becomes the empty list or
 * map, or {@code false}; whether a null string is acceptable is up to the
 * record's constructor.
 *
 * @param <R> record type */
public final class RowCodec<R> {
  /** Name of the column that says which catalog a row belongs to. */
  public static final String CATALOG_NAME = "CATALOG_NAME";

  private final Class<R> type;
  private final boolean catalogColumn;
  private final RecordComponent[] components;
  private final Constructor<R> constructor;
  private final List<String> columnNames;

  private RowCodec(Class<R> type, boolean catalogColumn) {
    if (!type.isRecord()) {
      throw new IllegalArgumentException(type + " is not a record");
    }
    this.type = type;
    this.catalogColumn = catalogColumn;
    this.components = type.getRecordComponents();
    final Class<?>[] parameterTypes = new Class<?>[components.length];
    final List<String> names = new ArrayList<>();
    if (catalogColumn) {
      names.add(CATALOG_NAME);
    }
    for (int i = 0; i < components.length; i++) {
      parameterTypes[i] = components[i].getType();
      names.add(columnName(components[i].getName()));
    }
    this.columnNames = List.copyOf(names);
    try {
      this.constructor = type.getDeclaredConstructor(parameterTypes);
    } catch (NoSuchMethodException e) {
      throw new IllegalArgumentException(e);
    }
  }

  /** Creates a codec.
   *
   * @param catalogColumn Whether rows start with a {@code CATALOG_NAME}
   *     column that is not part of the record */
  public static <R> RowCodec<R> of(Class<R> type, boolean catalogColumn) {
    return new RowCodec<>(type, catalogColumn);
  }

  /** Converts {@code schemaName} to {@code SCHEMA_NAME}. */
  public static String columnName(String componentName) {
    final StringBuilder b = new StringBuilder();
    for (int i = 0; i < componentName.length(); i++) {
      final char c = componentName.charAt(i);
      if (Character.isUpperCase(c)) {
        b.append('_');
      }
      b.append(c);
    }
    return b.toString().toUpperCase(Locale.ROOT);
  }

  public List<String> columnNames() {
    return columnNames;
  }

  public int ordinal(String columnName) {
    final int i = columnNames.indexOf(columnName);
    if (i < 0) {
      throw new IllegalArgumentException("No column " + columnName + " in "
          + type.getSimpleName());
    }
    return i;
  }

  public RelDataType rowType(RelDataTypeFactory typeFactory) {
    final RelDataTypeFactory.Builder builder = typeFactory.builder();
    int i = 0;
    if (catalogColumn) {
      builder.add(columnNames.get(i++), varchar(typeFactory));
    }
    for (RecordComponent component : components) {
      builder.add(columnNames.get(i++),
          sqlType(component.getGenericType(), typeFactory));
    }
    return builder.build();
  }

  private static RelDataType varchar(RelDataTypeFactory typeFactory) {
    return nullable(typeFactory,
        typeFactory.createSqlType(SqlTypeName.VARCHAR));
  }

  private static RelDataType nullable(RelDataTypeFactory typeFactory,
      RelDataType type) {
    return typeFactory.createTypeWithNullability(type, true);
  }

  private static RelDataType sqlType(Type javaType,
      RelDataTypeFactory typeFactory) {
    if (javaType == String.class) {
      return varchar(typeFactory);
    } else if (javaType == boolean.class || javaType == Boolean.class) {
      return nullable(typeFactory,
          typeFactory.createSqlType(SqlTypeName.BOOLEAN));
    } else if (javaType == Integer.class || javaType == int.class) {
      return nullable(typeFactory,
          typeFactory.createSqlType(SqlTypeName.INTEGER));
    } else if (javaType == Instant.class) {
      return nullable(typeFactory,
          typeFactory.createSqlType(SqlTypeName.TIMESTAMP, 3));
    } else if (javaType instanceof ParameterizedType p) {
      if (p.getRawType() == List.class) {
        final Type element = p.getActualTypeArguments()[0];
        // A list of columns is an array of column declarations.
        final RelDataType elementType =
            element == Column.class ? varchar(typeFactory)
                : sqlType(element, typeFactory);
        return nullable(typeFactory,
            typeFactory.createArrayType(elementType, -1));
      } else if (p.getRawType() == Map.class) {
        return nullable(typeFactory,
            typeFactory.createMapType(
                sqlType(p.getActualTypeArguments()[0], typeFactory),
                sqlType(p.getActualTypeArguments()[1], typeFactory)));
      }
    }
    throw new IllegalArgumentException("No SQL type for " + javaType);
  }

  /** Converts a record to a row. */
  public Object[] encode(String catalogName, R row) {
    final Object[] values = new Object[columnNames.size()];
    int i = 0;
    if (catalogColumn) {
      values[i++] = catalogName;
    }
    for (RecordComponent component : components) {
      final Object value;
      try {
        value = component.getAccessor().invoke(row);
      } catch (IllegalAccessException | InvocationTargetException e) {
        throw new IllegalStateException(e);
      }
      values[i++] = encodeValue(component.getGenericType(), value);
    }
    return values;
  }

  private static Object encodeValue(Type javaType, Object value) {
    if (value == null) {
      return null;
    } else if (value instanceof Instant instant) {
      return instant.toEpochMilli();
    } else if (value instanceof List<?> list
        && javaType instanceof ParameterizedType p
        && p.getActualTypeArguments()[0] == Column.class) {
      final List<Object> declarations = new ArrayList<>();
      for (Object element : list) {
        declarations.add(ColumnText.format((Column) element));
      }
      return declarations;
    }
    return value;
  }

  /** Returns the catalog a row names, or null. */
  public String catalogOf(Object[] row) {
    return catalogColumn && row[0] != null ? row[0].toString() : null;
  }

  /** Converts a row to a record. Throws {@link IllegalArgumentException} if
   * the record's constructor rejects the values. */
  public R decode(Object[] row) {
    final Object[] args = new Object[components.length];
    final int offset = catalogColumn ? 1 : 0;
    for (int i = 0; i < components.length; i++) {
      args[i] = decodeValue(components[i].getGenericType(), row[i + offset]);
    }
    try {
      return constructor.newInstance(args);
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof RuntimeException r) {
        throw r;
      }
      throw new IllegalStateException(e.getCause());
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Object decodeValue(Type javaType, Object value) {
    if (javaType == String.class) {
      return value == null ? null : value.toString();
    } else if (javaType == boolean.class) {
      return value != null && (Boolean) value;
    } else if (javaType == Boolean.class) {
      return value;
    } else if (javaType == Integer.class) {
      return value == null ? null : ((Number) value).intValue();
    } else if (javaType == int.class) {
      return value == null ? 0 : ((Number) value).intValue();
    } else if (javaType == Instant.class) {
      return value == null ? null
          : Instant.ofEpochMilli(((Number) value).longValue());
    } else if (javaType instanceof ParameterizedType p) {
      if (p.getRawType() == List.class) {
        final List<Object> list = new ArrayList<>();
        if (value != null) {
          final Type element = p.getActualTypeArguments()[0];
          for (Object o : (Iterable<?>) value) {
            if (element == Column.class) {
              list.add(ColumnText.parse(String.valueOf(o)));
            } else {
              list.add(decodeValue(element, o));
            }
          }
        }
        return list;
      } else if (p.getRawType() == Map.class) {
        final Map<Object, Object> map = new LinkedHashMap<>();
        if (value != null) {
          for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
            map.put(decodeValue(p.getActualTypeArguments()[0], e.getKey()),
                decodeValue(p.getActualTypeArguments()[1], e.getValue()));
          }
        }
        return map;
      }
    }
    throw new IllegalArgumentException("Cannot decode " + javaType);
  }
}
