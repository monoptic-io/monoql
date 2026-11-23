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

/** {@code CREATE [OR REPLACE] {SCHEMA | DATABASE} [IF NOT EXISTS] name
 * [COMMENT 'c'] [WITH (...)]}. */
public class SqlCreateSchema extends SqlCreate {
  private static final SqlOperator OPERATOR =
      SqlClauses.operator("CREATE SCHEMA", SqlKind.CREATE_SCHEMA);

  public final SqlIdentifier name;
  public final String comment;
  public final Map<String, String> options;

  public SqlCreateSchema(SqlParserPos pos, boolean replace,
      boolean ifNotExists, SqlIdentifier name, String comment,
      Map<String, String> options) {
    super(OPERATOR, pos, replace, ifNotExists);
    this.name = name;
    this.comment = comment;
    this.options = options;
  }

  @Override public List<SqlNode> getOperandList() {
    return List.of(name);
  }

  @Override public void unparse(SqlWriter writer, int leftPrec,
      int rightPrec) {
    writer.keyword(getReplace() ? "CREATE OR REPLACE SCHEMA"
        : "CREATE SCHEMA");
    if (ifNotExists) {
      writer.keyword("IF NOT EXISTS");
    }
    name.unparse(writer, 0, 0);
    SqlClauses.comment(writer, comment);
    SqlClauses.options(writer, options);
  }
}
