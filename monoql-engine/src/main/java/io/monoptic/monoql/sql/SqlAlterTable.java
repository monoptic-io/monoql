/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.sql;

import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlWriter;
import org.apache.calcite.sql.parser.SqlParserPos;

import java.util.Map;

/** {@code ALTER TABLE name action}, where the action is one of:
 *
 * <pre>
 * ADD COLUMN name type [NOT NULL] [COMMENT 'c']
 * DROP COLUMN name
 * RENAME TO name
 * SET (key = value, ...)
 * ADD [IF NOT EXISTS] PARTITION (key = value, ...) [WITH (...)]
 * DROP [IF EXISTS] PARTITION (key = value, ...)
 * </pre> */
public class SqlAlterTable extends SqlStatement {
  /** The action. */
  public enum Action {
    ADD_COLUMN, DROP_COLUMN, RENAME, SET_OPTIONS, ADD_PARTITION,
    DROP_PARTITION
  }

  public final SqlIdentifier name;
  public final Action action;
  /** For {@code ADD_COLUMN}. */
  public final SqlColumnDecl column;
  /** For {@code DROP_COLUMN}, the column; for {@code RENAME}, the new name. */
  public final SqlIdentifier target;
  /** For {@code ADD_PARTITION} and {@code DROP_PARTITION}. */
  public final Map<String, String> partition;
  /** For {@code SET_OPTIONS} and {@code ADD_PARTITION}. */
  public final Map<String, String> options;
  /** {@code IF NOT EXISTS} or {@code IF EXISTS}, for partitions. */
  public final boolean lenient;

  public SqlAlterTable(SqlParserPos pos, SqlIdentifier name, Action action,
      SqlColumnDecl column, SqlIdentifier target,
      Map<String, String> partition, Map<String, String> options,
      boolean lenient) {
    super("ALTER TABLE", pos);
    this.name = name;
    this.action = action;
    this.column = column;
    this.target = target;
    this.partition = partition;
    this.options = options;
    this.lenient = lenient;
  }

  @Override public void unparse(SqlWriter writer, int leftPrec,
      int rightPrec) {
    writer.keyword("ALTER TABLE");
    name.unparse(writer, 0, 0);
    switch (action) {
    case ADD_COLUMN:
      writer.keyword("ADD COLUMN");
      column.unparse(writer);
      break;
    case DROP_COLUMN:
      writer.keyword("DROP COLUMN");
      target.unparse(writer, 0, 0);
      break;
    case RENAME:
      writer.keyword("RENAME TO");
      target.unparse(writer, 0, 0);
      break;
    case SET_OPTIONS:
      writer.keyword("SET");
      SqlClauses.map(writer, options);
      break;
    case ADD_PARTITION:
      writer.keyword(lenient ? "ADD IF NOT EXISTS PARTITION"
          : "ADD PARTITION");
      SqlClauses.partition(writer, partition);
      SqlClauses.options(writer, options);
      break;
    case DROP_PARTITION:
      writer.keyword(lenient ? "DROP IF EXISTS PARTITION" : "DROP PARTITION");
      SqlClauses.partition(writer, partition);
      break;
    default:
      throw new AssertionError(action);
    }
  }
}
