/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.sql;

import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlWriter;
import org.apache.calcite.sql.parser.SqlParserPos;

/** {@code USE [CATALOG | SCHEMA | DATABASE] name}: sets the default catalog
 * or schema of the session. */
public class SqlUse extends SqlStatement {
  /** {@link ObjectKind#CATALOG}, {@link ObjectKind#SCHEMA}, or null if the
   * statement did not say. */
  public final ObjectKind kind;
  public final SqlIdentifier name;

  public SqlUse(SqlParserPos pos, ObjectKind kind, SqlIdentifier name) {
    super("USE", pos);
    this.kind = kind;
    this.name = name;
  }

  @Override public void unparse(SqlWriter writer, int leftPrec,
      int rightPrec) {
    writer.keyword("USE");
    if (kind != null) {
      writer.keyword(kind.sql);
    }
    name.unparse(writer, 0, 0);
  }
}
