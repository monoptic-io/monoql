/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.sql;

import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.parser.SqlParserPos;

import java.util.List;

/** Base class for monoql statements that are neither {@code CREATE} nor
 * {@code DROP}. */
public abstract class SqlStatement extends SqlCall {
  private final SqlOperator operator;

  protected SqlStatement(String name, SqlParserPos pos) {
    super(pos);
    this.operator = SqlClauses.operator(name, SqlKind.OTHER);
  }

  @Override public SqlOperator getOperator() {
    return operator;
  }

  @Override public List<SqlNode> getOperandList() {
    return List.of();
  }
}
