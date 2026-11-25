/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.sql;

import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlWriter;
import org.apache.calcite.sql.parser.SqlParserPos;

import java.util.List;

/** {@code GRANT} and {@code REVOKE}, of privileges and of roles.
 *
 * <pre>
 * GRANT privilege [, ...] ON [kind] object [USING [kind] source]
 *     TO grantee [, ...] [WITH GRANT OPTION]
 * REVOKE [GRANT OPTION FOR] privilege [, ...] ON [kind] object
 *     [USING [kind] source] FROM grantee [, ...]
 * GRANT [ROLE] role [, ...] TO grantee [, ...]
 * REVOKE [ROLE] role [, ...] FROM grantee [, ...]
 * </pre>
 *
 * <p>A grantee is {@code [USER | ROLE | GROUP] name}. A privilege is one or
 * more words ({@code SELECT}, {@code CREATE TABLE}, {@code ALL PRIVILEGES}),
 * or a quoted name. {@code USING} names where the rows written may have
 * been read from: a catalog, a schema, or a table or view. */
public class SqlGrant extends SqlStatement {
  /** What the statement does. */
  public enum Action {
    GRANT, REVOKE,
    /** {@code REVOKE GRANT OPTION FOR}: keeps the privilege, removes the
     * right to grant it onward. */
    REVOKE_GRANT_OPTION
  }

  /** A principal. {@code type} is {@code USER}, {@code ROLE}, {@code GROUP}
   * or {@code PRINCIPAL}. */
  public record Grantee(String type, String name) {
  }

  public final Action action;
  /** Privileges; or, if {@link #object} is null, role names. */
  public final List<String> items;
  /** Kind of {@link #object}; null for a role grant. */
  public final ObjectKind objectKind;
  /** Object the privileges are on; null for a role grant. */
  public final SqlIdentifier object;
  /** Kind of {@link #source}; null if there is none. */
  public final ObjectKind sourceKind;
  /** What the rows written may have been read from; null for anything. */
  public final SqlIdentifier source;
  public final List<Grantee> grantees;
  public final boolean grantOption;

  public SqlGrant(SqlParserPos pos, Action action, List<String> items,
      ObjectKind objectKind, SqlIdentifier object, ObjectKind sourceKind,
      SqlIdentifier source, List<Grantee> grantees, boolean grantOption) {
    super("GRANT", pos);
    this.action = action;
    this.items = items;
    this.objectKind = objectKind;
    this.object = object;
    this.sourceKind = sourceKind;
    this.source = source;
    this.grantees = grantees;
    this.grantOption = grantOption;
  }

  /** Whether this grants or revokes roles rather than privileges. */
  public boolean isRoleGrant() {
    return object == null;
  }

  @Override public void unparse(SqlWriter writer, int leftPrec,
      int rightPrec) {
    writer.keyword(action == Action.GRANT ? "GRANT" : "REVOKE");
    if (action == Action.REVOKE_GRANT_OPTION) {
      writer.keyword("GRANT OPTION FOR");
    }
    if (isRoleGrant()) {
      writer.keyword("ROLE");
    }
    for (int i = 0; i < items.size(); i++) {
      if (i > 0) {
        writer.print(", ");
      }
      writer.identifier(items.get(i), true);
    }
    if (object != null) {
      writer.keyword("ON");
      writer.keyword(objectKind.sql);
      object.unparse(writer, 0, 0);
    }
    if (source != null) {
      writer.keyword("USING");
      writer.keyword(sourceKind.sql);
      source.unparse(writer, 0, 0);
    }
    writer.keyword(action == Action.GRANT ? "TO" : "FROM");
    for (int i = 0; i < grantees.size(); i++) {
      if (i > 0) {
        writer.print(", ");
      }
      final Grantee g = grantees.get(i);
      if (!g.type.equals("PRINCIPAL")) {
        writer.keyword(g.type);
      }
      writer.identifier(g.name, true);
    }
    if (grantOption) {
      writer.keyword("WITH GRANT OPTION");
    }
  }
}
