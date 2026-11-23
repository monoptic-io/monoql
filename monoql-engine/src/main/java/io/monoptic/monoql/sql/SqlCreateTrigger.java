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
import org.apache.calcite.util.ImmutableNullableList;

import java.util.List;
import java.util.Map;

/** {@code CREATE [OR REPLACE] TRIGGER [IF NOT EXISTS] name
 * [{BEFORE | AFTER} event [OR event]...] [ON table]
 * [SCHEDULE 'cron'] [COMMENT 'c'] [WITH (...)]
 * {AS 'action' | AS statement | EXECUTE statement}}.
 *
 * <p>An action given as a statement is checked by the parser and stored as
 * SQL text. */
public class SqlCreateTrigger extends SqlCreate {
  private static final SqlOperator OPERATOR =
      SqlClauses.operator("CREATE TRIGGER", SqlKind.OTHER_DDL);

  public final SqlIdentifier name;
  public final String timing;
  public final List<String> events;
  public final SqlIdentifier table;
  public final String schedule;
  public final String comment;
  public final Map<String, String> options;
  public final String action;

  public SqlCreateTrigger(SqlParserPos pos, boolean replace,
      boolean ifNotExists, SqlIdentifier name, String timing,
      List<String> events, SqlIdentifier table, String schedule,
      String comment, Map<String, String> options, String action) {
    super(OPERATOR, pos, replace, ifNotExists);
    this.name = name;
    this.timing = timing;
    this.events = events;
    this.table = table;
    this.schedule = schedule;
    this.comment = comment;
    this.options = options;
    this.action = action;
  }

  @Override public List<SqlNode> getOperandList() {
    return ImmutableNullableList.of(name, table);
  }

  @Override public void unparse(SqlWriter writer, int leftPrec,
      int rightPrec) {
    writer.keyword(getReplace() ? "CREATE OR REPLACE TRIGGER"
        : "CREATE TRIGGER");
    if (ifNotExists) {
      writer.keyword("IF NOT EXISTS");
    }
    name.unparse(writer, 0, 0);
    if (timing != null) {
      writer.keyword(timing);
      writer.keyword(String.join(" OR ", events));
    }
    if (table != null) {
      writer.keyword("ON");
      table.unparse(writer, 0, 0);
    }
    if (schedule != null) {
      writer.keyword("SCHEDULE");
      SqlClauses.string(writer, schedule);
    }
    SqlClauses.comment(writer, comment);
    SqlClauses.options(writer, options);
    writer.keyword("AS");
    SqlClauses.string(writer, action);
  }
}
