/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.sql;

import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlSpecialOperator;
import org.apache.calcite.sql.SqlWriter;
import org.apache.calcite.sql.type.SqlTypeName;

import java.util.Map;

/** Helpers shared by the statement nodes: the {@code COMMENT} and
 * {@code WITH (...)} clauses, and literal handling. */
public final class SqlClauses {
  private SqlClauses() {
  }

  static SqlOperator operator(String name, SqlKind kind) {
    return new SqlSpecialOperator(name, kind);
  }

  /** Returns the value of a literal as a string: the content of a character
   * literal, or the text of any other. Used for option values, which are
   * always strings in the catalog. */
  public static String text(SqlNode literal) {
    final SqlLiteral l = (SqlLiteral) literal;
    if (SqlTypeName.CHAR_TYPES.contains(l.getTypeName())) {
      return l.getValueAs(String.class);
    }
    if (l.getTypeName() == SqlTypeName.BOOLEAN) {
      return String.valueOf(l.booleanValue());
    }
    return l.toValue();
  }

  static void string(SqlWriter writer, String value) {
    writer.literal("'" + value.replace("'", "''") + "'");
  }

  static void comment(SqlWriter writer, String comment) {
    if (comment != null) {
      writer.keyword("COMMENT");
      string(writer, comment);
    }
  }

  static void options(SqlWriter writer, Map<String, String> options) {
    if (options.isEmpty()) {
      return;
    }
    writer.keyword("WITH");
    map(writer, options);
  }

  /** Writes a partition specification: like {@link #map}, but the keys
   * are column names. */
  static void partition(SqlWriter writer, Map<String, String> spec) {
    final SqlWriter.Frame frame = writer.startList("(", ")");
    for (Map.Entry<String, String> e : spec.entrySet()) {
      writer.sep(",");
      writer.identifier(e.getKey(), true);
      writer.print("= ");
      string(writer, e.getValue());
    }
    writer.endList(frame);
  }

  /** Writes a parenthesized, comma-separated list of names. */
  static void names(SqlWriter writer, Iterable<SqlNode> names) {
    final SqlWriter.Frame frame = writer.startList("(", ")");
    for (SqlNode name : names) {
      writer.sep(",");
      name.unparse(writer, 0, 0);
    }
    writer.endList(frame);
  }

  static void map(SqlWriter writer, Map<String, String> map) {
    final SqlWriter.Frame frame = writer.startList("(", ")");
    for (Map.Entry<String, String> e : map.entrySet()) {
      writer.sep(",");
      string(writer, e.getKey());
      writer.print("= ");
      string(writer, e.getValue());
    }
    writer.endList(frame);
  }
}
