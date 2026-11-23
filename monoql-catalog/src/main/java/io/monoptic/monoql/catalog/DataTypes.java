/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.catalog;

import org.apache.calcite.config.CalciteConnectionConfig;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.prepare.CalciteCatalogReader;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlDataTypeSpec;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.sql.validate.SqlValidatorUtil;

import java.util.List;

/** Converts between the SQL type strings stored in {@link Column#dataType()}
 * and Calcite's {@link RelDataType}.
 *
 * <p>Types are strings in the catalog API so that catalogs need not know
 * anything about the engine's type system, and so that they read naturally in
 * {@code metadata.COLUMNS}. */
public final class DataTypes {
  private DataTypes() {
  }

  /** Parses a type such as {@code DECIMAL(10, 2)} or {@code VARCHAR ARRAY}.
   *
   * @throws CatalogException if the string is not a type */
  public static RelDataType parse(String dataType, boolean nullable,
      RelDataTypeFactory typeFactory) {
    final SqlNode node;
    try {
      node = SqlParser.create("CAST(NULL AS " + dataType + ")")
          .parseExpression();
    } catch (SqlParseException e) {
      throw new CatalogException("Invalid data type '" + dataType + "'", e);
    }
    final SqlDataTypeSpec spec = (SqlDataTypeSpec) ((SqlCall) node).operand(1);
    final SqlValidator validator =
        SqlValidatorUtil.newValidator(SqlStdOperatorTable.instance(),
            new CalciteCatalogReader(CalciteSchema.createRootSchema(false),
                List.of(), typeFactory, CalciteConnectionConfig.DEFAULT),
            typeFactory, SqlValidator.Config.DEFAULT);
    final RelDataType type;
    try {
      type = spec.deriveType(validator);
    } catch (RuntimeException e) {
      throw new CatalogException("Invalid data type '" + dataType + "'", e);
    }
    return typeFactory.createTypeWithNullability(type, nullable);
  }

  /** Builds the row type of a list of columns. */
  public static RelDataType rowType(List<Column> columns,
      RelDataTypeFactory typeFactory) {
    final RelDataTypeFactory.Builder builder = typeFactory.builder();
    for (Column column : columns) {
      builder.add(column.columnName(),
          parse(column.dataType(), column.nullable(), typeFactory));
    }
    return builder.build();
  }

  /** Converts a row type to columns; the inverse of {@link #rowType}. */
  public static List<Column> columns(RelDataType rowType) {
    return rowType.getFieldList().stream()
        .map(f ->
            new Column(f.getName(), format(f.getType()),
                f.getType().isNullable(), null))
        .toList();
  }

  /** Formats a type as a string that {@link #parse} accepts. Nullability is
   * not part of the string. */
  public static String format(RelDataType type) {
    final SqlTypeName name = type.getSqlTypeName();
    switch (name) {
    case ARRAY:
      return format(elementType(type)) + " ARRAY";
    case MULTISET:
      return format(elementType(type)) + " MULTISET";
    case MAP:
      return "MAP<" + format(keyType(type)) + ", " + format(valueType(type))
          + ">";
    case ROW:
      final StringBuilder b = new StringBuilder("ROW(");
      for (RelDataTypeField field : type.getFieldList()) {
        if (field.getIndex() > 0) {
          b.append(", ");
        }
        b.append('"').append(field.getName().replace("\"", "\"\""))
            .append("\" ").append(format(field.getType()));
      }
      return b.append(')').toString();
    case ANY:
    case OTHER:
      return "ANY";
    default:
      break;
    }
    final StringBuilder b = new StringBuilder(name.getName());
    if (name.allowsPrec()
        && type.getPrecision() != RelDataType.PRECISION_NOT_SPECIFIED) {
      // Insert the precision after the first word, so that it lands in the
      // right place for e.g. TIMESTAMP(3) WITH LOCAL TIME ZONE.
      final String s = name.getName();
      final int space = s.indexOf('_');
      final StringBuilder p = new StringBuilder("(").append(type.getPrecision());
      if (name.allowsScale() && type.getScale() != 0
          && type.getScale() != RelDataType.SCALE_NOT_SPECIFIED) {
        p.append(", ").append(type.getScale());
      }
      p.append(')');
      b.setLength(0);
      if (space < 0) {
        b.append(s).append(p);
      } else {
        b.append(s, 0, space).append(p).append(s, space, s.length());
      }
    }
    return b.toString().replace('_', ' ');
  }

  private static RelDataType elementType(RelDataType type) {
    return java.util.Objects.requireNonNull(type.getComponentType());
  }

  private static RelDataType keyType(RelDataType type) {
    return java.util.Objects.requireNonNull(type.getKeyType());
  }

  private static RelDataType valueType(RelDataType type) {
    return java.util.Objects.requireNonNull(type.getValueType());
  }
}
