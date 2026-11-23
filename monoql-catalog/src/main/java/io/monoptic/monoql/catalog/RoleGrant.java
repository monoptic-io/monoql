/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.catalog;

/** A row of {@code metadata.ROLE_GRANTS}: membership of a principal in a
 * role. {@code GRANT ROLE r TO USER u} inserts one. */
public record RoleGrant(String roleName, String granteeType, String grantee)
    implements MetaRow {
  public RoleGrant {
    Rows.required(roleName, "roleName");
    Rows.required(granteeType, "granteeType");
    Rows.required(grantee, "grantee");
  }

  @Override public Key key() {
    return Key.of(roleName, granteeType, grantee);
  }
}
