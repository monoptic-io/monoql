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

import java.util.List;
import java.util.Map;

/** {@code CREATE [OR REPLACE] [MATERIALIZED] VIEW [IF NOT EXISTS] name
 * [(c, ...)] [SCHEDULE 'cron'] [COMMENT 'c'] [WITH (...)] AS query}.
 *
 * <p>{@code SCHEDULE} and {@code WITH} apply only to materialized views; the
 * parser does not accept them on a plain view. */
public class SqlCreateView extends SqlCreate {
  private static final SqlOperator OPERATOR =
      SqlClauses.operator("CREATE VIEW", SqlKind.CREATE_VIEW);

  public final boolean materialized;
  public final SqlIdentifier name;
  public final SqlNodeList columnList;
  public final String schedule;
  public final String comment;
  public final Map<String, String> options;
  public final SqlNode query;

  public SqlCreateView(SqlParserPos pos, boolean replace, boolean ifNotExists,
      boolean materialized, SqlIdentifier name, SqlNodeList columnList,
      String schedule, String comment, Map<String, String> options,
      SqlNode query) {
    super(OPERATOR, pos, replace, ifNotExists);
    this.materialized = materialized;
    this.name = name;
    this.columnList = columnList;
    this.schedule = schedule;
    this.comment = comment;
    this.options = options;
    this.query = query;
  }

  @Override public List<SqlNode> getOperandList() {
    return List.of(name, columnList, query);
  }

  @Override public void unparse(SqlWriter writer, int leftPrec,
      int rightPrec) {
    writer.keyword(getReplace() ? "CREATE OR REPLACE" : "CREATE");
    writer.keyword(materialized ? "MATERIALIZED VIEW" : "VIEW");
    if (ifNotExists) {
      writer.keyword("IF NOT EXISTS");
    }
    name.unparse(writer, 0, 0);
    if (!columnList.isEmpty()) {
      SqlClauses.names(writer, columnList);
    }
    if (schedule != null) {
      writer.keyword("SCHEDULE");
      SqlClauses.string(writer, schedule);
    }
    SqlClauses.comment(writer, comment);
    SqlClauses.options(writer, options);
    writer.keyword("AS");
    writer.newlineAndIndent();
    query.unparse(writer, 0, 0);
  }
}
