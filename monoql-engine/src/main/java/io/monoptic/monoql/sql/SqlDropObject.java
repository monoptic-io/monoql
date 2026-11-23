/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.sql;

import org.apache.calcite.sql.SqlDrop;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlWriter;
import org.apache.calcite.sql.parser.SqlParserPos;

import java.util.List;

/** {@code DROP kind [IF EXISTS] name}, for every {@link ObjectKind} except
 * {@code COLUMN}. */
public class SqlDropObject extends SqlDrop {
  private static final SqlOperator OPERATOR =
      SqlClauses.operator("DROP", SqlKind.OTHER_DDL);

  public final ObjectKind kind;
  public final SqlIdentifier name;

  public SqlDropObject(SqlParserPos pos, ObjectKind kind, boolean ifExists,
      SqlIdentifier name) {
    super(OPERATOR, pos, ifExists);
    this.kind = kind;
    this.name = name;
  }

  @Override public List<SqlNode> getOperandList() {
    return List.of(name);
  }

  @Override public void unparse(SqlWriter writer, int leftPrec,
      int rightPrec) {
    writer.keyword("DROP");
    writer.keyword(kind.sql);
    if (ifExists) {
      writer.keyword("IF EXISTS");
    }
    name.unparse(writer, 0, 0);
  }
}
