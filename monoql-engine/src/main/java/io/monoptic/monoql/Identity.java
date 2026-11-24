/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Who a session acts as: a user, and the groups the user belongs to.
 *
 * <p>monoql does not authenticate anyone, and has no source of group
 * membership. The application that builds a session has established both by
 * its own means, and vouches for them.
 *
 * <p>Names are compared exactly with the grantees of grants. An unquoted
 * name in a {@code GRANT} statement is stored in upper case, so
 * {@code GRANT ... TO USER ann} is a grant to {@code "ANN"}.
 *
 * @param user Name of the user
 * @param groups Names of the user's groups; a grant to {@code GROUP g}
 *     applies to every identity whose groups include {@code g} */
public record Identity(String user, Set<String> groups) {
  public Identity {
    Objects.requireNonNull(user, "user");
    groups = Collections.unmodifiableSet(new LinkedHashSet<>(groups));
  }

  public static Identity of(String user, String... groups) {
    return new Identity(user, new LinkedHashSet<>(Arrays.asList(groups)));
  }
}
