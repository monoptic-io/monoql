/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.sql;

import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlWriter;
import org.apache.calcite.sql.parser.SqlParserPos;

import java.util.Map;

/** Control commands: {@code PAUSE} and {@code RESUME} of a trigger or
 * materialized view, {@code FIRE TRIGGER name [WITH (...)]}, and
 * {@code REFRESH MATERIALIZED VIEW name}. */
public class SqlControl extends SqlStatement {
  /** The command. */
  public enum Verb { PAUSE, RESUME, FIRE, REFRESH }

  public final Verb verb;
  /** {@link ObjectKind#TRIGGER} or {@link ObjectKind#MATERIALIZED_VIEW}. */
  public final ObjectKind kind;
  public final SqlIdentifier name;
  /** Parameters of a {@code FIRE}; otherwise empty. */
  public final Map<String, String> options;

  public SqlControl(SqlParserPos pos, Verb verb, ObjectKind kind,
      SqlIdentifier name, Map<String, String> options) {
    super(verb.name(), pos);
    this.verb = verb;
    this.kind = kind;
    this.name = name;
    this.options = options;
  }

  @Override public void unparse(SqlWriter writer, int leftPrec,
      int rightPrec) {
    writer.keyword(verb.name());
    writer.keyword(kind.sql);
    name.unparse(writer, 0, 0);
    SqlClauses.options(writer, options);
  }
}
