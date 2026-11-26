/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.engine;

import io.monoptic.monoql.Monoql;
import io.monoptic.monoql.catalog.CatalogException;
import io.monoptic.monoql.catalog.Column;
import io.monoptic.monoql.catalog.DataTypes;
import io.monoptic.monoql.sql.SqlColumnDecl;
import io.monoptic.monoql.sql.SqlCreateTable;

import org.apache.calcite.sql.dialect.CalciteSqlDialect;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;

/** Writes a {@link Column} as the text of a column declaration, and reads
 * it back: {@code "ID" INTEGER NOT NULL COMMENT 'the key'}.
 *
 * <p>This is how the columns of a table appear in
 * {@code METADATA.TABLES.COLUMNS}, which is an array of such strings. It is
 * the same syntax as in {@code CREATE TABLE}, and it is read by the same
 * parser. (An array of rows would be the textbook choice, but declarations
 * are easier to write by hand, and Calcite's runtime handles them better.) */
final class ColumnText {
  private ColumnText() {
  }

  static String format(Column column) {
    final StringBuilder b = new StringBuilder(SqlText.id(column.columnName()))
        .append(' ').append(column.dataType());
    if (!column.nullable()) {
      b.append(" NOT NULL");
    }
    if (column.comment() != null) {
      b.append(" COMMENT ").append(SqlText.str(column.comment()));
    }
    return b.toString();
  }

  static Column parse(String text) {
    final SqlCreateTable create;
    try {
      create = (SqlCreateTable) SqlParser.create(
          "CREATE TABLE T (" + text + ")", Monoql.parserConfig()).parseStmt();
    } catch (SqlParseException | ClassCastException e) {
      throw new CatalogException("Invalid column declaration '" + text
          + "': " + e.getMessage(), e);
    }
    if (create.columns.size() != 1 || !create.primaryKey.isEmpty()) {
      throw new CatalogException("Invalid column declaration '" + text
          + "': expected one column, as \"name type [NOT NULL] "
          + "[COMMENT 'text']\"");
    }
    return of(create.columns.get(0));
  }

  static Column of(SqlColumnDecl decl) {
    // Kept under the type's own name, however the declaration wrote it.
    return new Column(decl.name().getSimple(),
        DataTypes.canonical(
            decl.type().toSqlString(CalciteSqlDialect.DEFAULT).getSql()),
        decl.nullable(), decl.comment());
  }
}
