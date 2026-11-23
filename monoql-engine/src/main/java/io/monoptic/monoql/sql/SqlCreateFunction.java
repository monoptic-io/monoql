/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.sql;

import org.apache.calcite.sql.SqlCreate;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlWriter;
import org.apache.calcite.sql.parser.SqlParserPos;

import java.util.List;
import java.util.Map;

/** {@code CREATE [OR REPLACE] FUNCTION [IF NOT EXISTS] name
 * [LANGUAGE lang] [COMMENT 'c'] [WITH (...)] AS 'definition'}.
 *
 * <p>The language defaults to {@code JAVA}, for which the definition is a
 * class name, optionally followed by {@code #method}. */
public class SqlCreateFunction extends SqlCreate {
  private static final SqlOperator OPERATOR =
      SqlClauses.operator("CREATE FUNCTION", SqlKind.CREATE_FUNCTION);

  public final SqlIdentifier name;
  public final String language;
  public final String comment;
  public final Map<String, String> options;
  public final String definition;

  public SqlCreateFunction(SqlParserPos pos, boolean replace,
      boolean ifNotExists, SqlIdentifier name, String language,
      String comment, Map<String, String> options, String definition) {
    super(OPERATOR, pos, replace, ifNotExists);
    this.name = name;
    this.language = language;
    this.comment = comment;
    this.options = options;
    this.definition = definition;
  }

  @Override public List<SqlNode> getOperandList() {
    return List.of(name);
  }

  @Override public void unparse(SqlWriter writer, int leftPrec,
      int rightPrec) {
    writer.keyword(getReplace() ? "CREATE OR REPLACE FUNCTION"
        : "CREATE FUNCTION");
    if (ifNotExists) {
      writer.keyword("IF NOT EXISTS");
    }
    name.unparse(writer, 0, 0);
    writer.keyword("LANGUAGE");
    writer.identifier(language, false);
    SqlClauses.comment(writer, comment);
    SqlClauses.options(writer, options);
    writer.keyword("AS");
    SqlClauses.string(writer, definition);
  }
}
