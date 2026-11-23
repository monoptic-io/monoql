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

/** {@code CREATE [OR REPLACE] {ROLE | USER} [IF NOT EXISTS] name
 * [COMMENT 'c'] [WITH (...)]}. */
public class SqlCreatePrincipal extends SqlCreate {
  private static final SqlOperator OPERATOR =
      SqlClauses.operator("CREATE PRINCIPAL", SqlKind.OTHER_DDL);

  /** {@link ObjectKind#ROLE} or {@link ObjectKind#USER}. */
  public final ObjectKind kind;
  public final SqlIdentifier name;
  public final String comment;
  public final Map<String, String> options;

  public SqlCreatePrincipal(SqlParserPos pos, boolean replace,
      boolean ifNotExists, ObjectKind kind, SqlIdentifier name,
      String comment, Map<String, String> options) {
    super(OPERATOR, pos, replace, ifNotExists);
    this.kind = kind;
    this.name = name;
    this.comment = comment;
    this.options = options;
  }

  @Override public List<SqlNode> getOperandList() {
    return List.of(name);
  }

  @Override public void unparse(SqlWriter writer, int leftPrec,
      int rightPrec) {
    writer.keyword(getReplace() ? "CREATE OR REPLACE" : "CREATE");
    writer.keyword(kind.sql);
    if (ifNotExists) {
      writer.keyword("IF NOT EXISTS");
    }
    name.unparse(writer, 0, 0);
    SqlClauses.comment(writer, comment);
    SqlClauses.options(writer, options);
  }
}
