/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.catalog;

import java.util.Map;

/** A row of {@code metadata.USERS}. Users belong to a catalog. */
public record UserInfo(String userName, String comment,
    Map<String, String> options) implements MetaRow {
  public UserInfo {
    Rows.required(userName, "userName");
    options = Rows.map(options);
  }

  @Override public Key key() {
    return Key.of(userName);
  }
}
