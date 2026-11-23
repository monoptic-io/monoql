/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.catalog;

import java.util.Map;

/** A row of {@code metadata.ROLES}. Roles belong to a catalog. */
public record RoleInfo(String roleName, String comment,
    Map<String, String> options) implements MetaRow {
  public RoleInfo {
    Rows.required(roleName, "roleName");
    options = Rows.map(options);
  }

  @Override public Key key() {
    return Key.of(roleName);
  }
}
