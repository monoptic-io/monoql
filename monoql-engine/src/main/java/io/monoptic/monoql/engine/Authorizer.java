/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.engine;

import io.monoptic.monoql.catalog.AccessDeniedException;
import io.monoptic.monoql.catalog.Catalog;
import io.monoptic.monoql.catalog.Grant;
import io.monoptic.monoql.catalog.RoleGrant;
import io.monoptic.monoql.catalog.UnsupportedException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Decides whether the user of a session may do what a statement asks, by
 * reading {@code GRANTS} and {@code ROLE_GRANTS}.
 *
 * <p>The rules:
 *
 * <ul>
 * <li>A session with no user is not checked.
 * <li>A catalog with no readable {@code GRANTS} table is not checked: there
 *     is nothing to check against, and its backend is assumed to enforce
 *     its own rules.
 * <li>A grant applies to the user if it names the user, or a role the user
 *     holds, directly or through other roles.
 * <li>A grant on a catalog or schema covers everything in it, and
 *     {@code ALL PRIVILEGES} covers every privilege.
 * <li>Reading a table or view takes {@code SELECT} on it; writing one takes
 *     {@code INSERT}, {@code UPDATE} or {@code DELETE}. A view is read with
 *     the rights of whoever defined it: its tables are not checked again.
 * <li>Changing metadata (which is what DDL, DCL and control statements do)
 *     takes {@code ALL PRIVILEGES} on the schema the row belongs to, or on
 *     the catalog. A row of {@code GRANTS} may also be written by someone
 *     who holds that privilege on that object with the grant option.
 * <li>Reading metadata is open to every user.
 * </ul> */
final class Authorizer {
  static final String ALL = "ALL PRIVILEGES";
  private static final String CATALOG = "CATALOG";
  private static final String SCHEMA = "SCHEMA";
  /** Kinds of object that share the namespace of tables. */
  private static final Set<String> RELATIONS =
      Set.of("TABLE", "VIEW", "MATERIALIZED VIEW");

  private final String user;
  private final CatalogRegistry registry;

  Authorizer(String user, CatalogRegistry registry) {
    this.user = user;
    this.registry = registry;
  }

  boolean enabled() {
    return user != null;
  }

  /** The grants of a catalog that apply to the user; null if the catalog
   * is not checked. */
  private List<Grant> grants(String catalogName) {
    if (user == null) {
      return null;
    }
    final Catalog catalog = registry.catalog(catalogName);
    if (catalog == null) {
      return null;
    }
    final Iterable<Grant> all;
    try {
      all = catalog.grants().scan();
    } catch (UnsupportedException e) {
      return null;
    }
    final Set<String> roles = roles(catalog);
    final List<Grant> grants = new ArrayList<>();
    for (Grant g : all) {
      final String type = g.granteeType();
      final boolean any = type.equals(Grant.PRINCIPAL);
      if ((any || type.equals("USER")) && g.grantee().equals(user)
          || (any || type.equals("ROLE")) && roles.contains(g.grantee())) {
        grants.add(g);
      }
    }
    return grants;
  }

  /** The roles the user holds, directly or through other roles. */
  private Set<String> roles(Catalog catalog) {
    final Set<String> roles = new HashSet<>();
    final List<RoleGrant> all = new ArrayList<>();
    try {
      catalog.roleGrants().scan().forEach(all::add);
    } catch (UnsupportedException e) {
      return roles;
    }
    boolean grew = true;
    while (grew) {
      grew = false;
      for (RoleGrant g : all) {
        final String type = g.granteeType();
        final boolean any = type.equals(Grant.PRINCIPAL);
        if ((any || type.equals("USER")) && g.grantee().equals(user)
            || (any || type.equals("ROLE")) && roles.contains(g.grantee())) {
          grew |= roles.add(g.roleName());
        }
      }
    }
    return roles;
  }

  /** Whether a grant reaches an object. A null {@code name} means the
   * schema itself; a null {@code schema}, the catalog itself. */
  private static boolean covers(Grant g, String type, String schema,
      String name) {
    if (g.objectType().equals(CATALOG)) {
      return true;
    }
    if (schema == null || !schema.equals(g.objectSchema())) {
      return false;
    }
    if (g.objectType().equals(SCHEMA)) {
      return true;
    }
    return name != null && name.equals(g.objectName())
        && (g.objectType().equals(type)
            || RELATIONS.contains(g.objectType()) && RELATIONS.contains(type));
  }

  private static boolean holds(List<Grant> grants, String privilege,
      boolean withGrantOption, String type, String schema, String name) {
    for (Grant g : grants) {
      if ((g.privilege().equals(privilege) || g.privilege().equals(ALL))
          && (g.grantable() || !withGrantOption)
          && covers(g, type, schema, name)) {
        return true;
      }
    }
    return false;
  }

  private static String path(String catalog, String schema, String name) {
    return catalog + (schema == null ? "" : "." + schema)
        + (name == null ? "" : "." + name);
  }

  /** Checks that the user may read or write the rows of a table or view.
   *
   * @param privilege {@code SELECT}, {@code INSERT}, {@code UPDATE} or
   *     {@code DELETE} */
  void checkRelation(String catalog, String schema, String name,
      String privilege) {
    final List<Grant> grants = grants(catalog);
    if (grants != null
        && !holds(grants, privilege, false, "TABLE", schema, name)) {
      throw new AccessDeniedException("User " + user + " lacks " + privilege
          + " on " + path(catalog, schema, name));
    }
  }

  /** Checks that the user may change a row of a metadata table.
   *
   * @param table Name of the metadata table
   * @param schema Schema the row belongs to, or null if it belongs to the
   *     catalog as a whole */
  void checkMetadata(String table, String catalog, String schema) {
    final List<Grant> grants = grants(catalog);
    if (grants != null && !holds(grants, ALL, false, SCHEMA, schema, null)) {
      throw denied(table, catalog, schema);
    }
  }

  /** Checks that the user may write a row of {@code GRANTS}: as for any
   * metadata, or by holding the same privilege on the same object with the
   * grant option. */
  void checkGrant(String catalog, String privilege, String objectType,
      String objectSchema, String objectName) {
    final List<Grant> grants = grants(catalog);
    if (grants != null
        && !holds(grants, ALL, false, SCHEMA, objectSchema, null)
        && !holds(grants, String.valueOf(privilege), true,
            objectType, objectSchema, objectName)) {
      throw new AccessDeniedException("User " + user + " may not grant or"
          + " revoke " + privilege + " on "
          + path(catalog, objectSchema, objectName) + ": that takes " + ALL
          + " on its schema or catalog, or " + privilege
          + " WITH GRANT OPTION");
    }
  }

  /** Checks that the session may change which catalogs are mounted. */
  void checkCatalogs() {
    if (user != null) {
      throw new AccessDeniedException("User " + user + " may not create or"
          + " drop catalogs; only a session without a user may");
    }
  }

  private AccessDeniedException denied(String table, String catalog,
      String schema) {
    return new AccessDeniedException("User " + user + " lacks " + ALL
        + " on " + (schema == null ? "catalog " : "schema ")
        + path(catalog, schema, null) + ", which changing METADATA." + table
        + " takes");
  }
}
