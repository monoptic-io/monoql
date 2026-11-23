/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.sql;

import org.apache.calcite.sql.SqlDataTypeSpec;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlWriter;

/** A column in {@code CREATE TABLE} or {@code ALTER TABLE ... ADD COLUMN}:
 * {@code name type [NOT NULL] [COMMENT 'c']}. */
public record SqlColumnDecl(SqlIdentifier name, SqlDataTypeSpec type,
    boolean nullable, String comment) {
  void unparse(SqlWriter writer) {
    name.unparse(writer, 0, 0);
    type.unparse(writer, 0, 0);
    if (!nullable) {
      writer.keyword("NOT NULL");
    }
    SqlClauses.comment(writer, comment);
  }
}
