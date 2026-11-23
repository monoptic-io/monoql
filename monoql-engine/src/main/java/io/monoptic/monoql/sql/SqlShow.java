/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.sql;

import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlWriter;
import org.apache.calcite.sql.parser.SqlParserPos;

/** {@code SHOW things [{IN | FROM} scope] [LIKE 'pattern']}; and
 * {@code SHOW GRANTS [ON [kind] object] [TO grantee]}. */
public class SqlShow extends SqlStatement {
  /** What to show. Each is one table of the {@code metadata} schema. */
  public enum What {
    CATALOGS, SCHEMAS, TABLES, VIEWS, MATERIALIZED_VIEWS("MATERIALIZED VIEWS"),
    COLUMNS, PARTITIONS, FUNCTIONS, TRIGGERS, ROLES, USERS, GRANTS, SETTINGS;

    public final String sql;

    What() {
      this.sql = name();
    }

    What(String sql) {
      this.sql = sql;
    }
  }

  public final What what;
  /** Catalog, schema or table to look in, or null for the current one. */
  public final SqlIdentifier scope;
  /** {@code LIKE} pattern on the name, or null. */
  public final String like;
  /** For {@code GRANTS}: kind of {@link #scope}. */
  public final ObjectKind objectKind;
  /** For {@code GRANTS}: restrict to one grantee, or null. */
  public final SqlGrant.Grantee grantee;

  public SqlShow(SqlParserPos pos, What what, SqlIdentifier scope,
      String like, ObjectKind objectKind, SqlGrant.Grantee grantee) {
    super("SHOW", pos);
    this.what = what;
    this.scope = scope;
    this.like = like;
    this.objectKind = objectKind;
    this.grantee = grantee;
  }

  @Override public void unparse(SqlWriter writer, int leftPrec,
      int rightPrec) {
    writer.keyword("SHOW");
    writer.keyword(what.sql);
    if (scope != null) {
      if (what == What.GRANTS) {
        writer.keyword("ON");
        writer.keyword(objectKind.sql);
      } else {
        writer.keyword("IN");
      }
      scope.unparse(writer, 0, 0);
    }
    if (grantee != null) {
      writer.keyword("TO");
      if (!grantee.type().equals("PRINCIPAL")) {
        writer.keyword(grantee.type());
      }
      writer.identifier(grantee.name(), true);
    }
    if (like != null) {
      writer.keyword("LIKE");
      SqlClauses.string(writer, like);
    }
  }
}
