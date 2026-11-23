/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.sql;

import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlWriter;
import org.apache.calcite.sql.parser.SqlParserPos;

/** {@code COMMENT ON kind name IS {'comment' | NULL}}. For a column, the
 * name is {@code [[catalog.]schema.]table.column}. */
public class SqlCommentOn extends SqlStatement {
  public final ObjectKind kind;
  public final SqlIdentifier name;
  /** The comment; null removes it. */
  public final String comment;

  public SqlCommentOn(SqlParserPos pos, ObjectKind kind, SqlIdentifier name,
      String comment) {
    super("COMMENT ON", pos);
    this.kind = kind;
    this.name = name;
    this.comment = comment;
  }

  @Override public void unparse(SqlWriter writer, int leftPrec,
      int rightPrec) {
    writer.keyword("COMMENT ON");
    writer.keyword(kind.sql);
    name.unparse(writer, 0, 0);
    writer.keyword("IS");
    if (comment == null) {
      writer.keyword("NULL");
    } else {
      SqlClauses.string(writer, comment);
    }
  }
}
