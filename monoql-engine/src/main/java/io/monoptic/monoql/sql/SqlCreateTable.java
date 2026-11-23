/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.sql;

import org.apache.calcite.sql.SqlCreate;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlWriter;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.util.ImmutableNullableList;

import java.util.List;
import java.util.Map;

/** {@code CREATE [OR REPLACE] TABLE [IF NOT EXISTS] name
 * [(column, ... [, PRIMARY KEY (c, ...)])]
 * [PARTITIONED BY (c, ...)] [COMMENT 'c'] [WITH (...)] [AS query]}.
 *
 * <p>Either the column list or the query must be present. */
public class SqlCreateTable extends SqlCreate {
  private static final SqlOperator OPERATOR =
      SqlClauses.operator("CREATE TABLE", SqlKind.CREATE_TABLE);

  public final SqlIdentifier name;
  public final List<SqlColumnDecl> columns;
  public final SqlNodeList primaryKey;
  public final SqlNodeList partitionKeys;
  public final String comment;
  public final Map<String, String> options;
  public final SqlNode query;

  public SqlCreateTable(SqlParserPos pos, boolean replace,
      boolean ifNotExists, SqlIdentifier name, List<SqlColumnDecl> columns,
      SqlNodeList primaryKey, SqlNodeList partitionKeys, String comment,
      Map<String, String> options, SqlNode query) {
    super(OPERATOR, pos, replace, ifNotExists);
    this.name = name;
    this.columns = columns;
    this.primaryKey = primaryKey;
    this.partitionKeys = partitionKeys;
    this.comment = comment;
    this.options = options;
    this.query = query;
  }

  @Override public List<SqlNode> getOperandList() {
    return ImmutableNullableList.of(name, primaryKey, partitionKeys, query);
  }

  @Override public void unparse(SqlWriter writer, int leftPrec,
      int rightPrec) {
    writer.keyword(getReplace() ? "CREATE OR REPLACE TABLE" : "CREATE TABLE");
    if (ifNotExists) {
      writer.keyword("IF NOT EXISTS");
    }
    name.unparse(writer, 0, 0);
    if (!columns.isEmpty()) {
      final SqlWriter.Frame frame = writer.startList("(", ")");
      for (SqlColumnDecl column : columns) {
        writer.sep(",");
        column.unparse(writer);
      }
      if (!primaryKey.isEmpty()) {
        writer.sep(",");
        writer.keyword("PRIMARY KEY");
        SqlClauses.names(writer, primaryKey);
      }
      writer.endList(frame);
    }
    if (!partitionKeys.isEmpty()) {
      writer.keyword("PARTITIONED BY");
      SqlClauses.names(writer, partitionKeys);
    }
    SqlClauses.comment(writer, comment);
    SqlClauses.options(writer, options);
    if (query != null) {
      writer.keyword("AS");
      writer.newlineAndIndent();
      query.unparse(writer, 0, 0);
    }
  }
}
