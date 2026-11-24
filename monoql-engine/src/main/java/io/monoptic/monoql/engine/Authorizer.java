/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.engine;

import io.monoptic.monoql.Identity;
import io.monoptic.monoql.catalog.AccessDeniedException;
import io.monoptic.monoql.catalog.Catalog;
import io.monoptic.monoql.catalog.Grant;
import io.monoptic.monoql.catalog.MetaTable;
import io.monoptic.monoql.catalog.RoleGrant;
import io.monoptic.monoql.catalog.UnsupportedException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;

/** Decides whether the user of a session may do what a statement asks, by
 * reading {@code GRANTS} and {@code ROLE_GRANTS}; and keeps {@code GRANTS}
 * in step with the objects it describes.
 *
 * <p>The rules:
 *
 * <ul>
 * <li>A session with no user is not checked.
 * <li>A catalog with no readable {@code GRANTS} table is not checked: there
 *     is nothing to check against, and its backend is assumed to enforce
 *     its own rules.
 * <li>A grant applies to the user if it names the user, a group the user
 *     belongs to, or a role that any of those holds, directly or through
 *     other roles.
 * <li>A grant on a catalog or schema covers everything in it.
 * <li>{@code OWNERSHIP} of something includes every privilege on it.
 *     {@code ALL PRIVILEGES} on a schema or catalog is the same thing;
 *     on a single object it is every privilege except ownership.
 * <li>Reading a table or view takes {@code SELECT} on it; writing one takes
 *     {@code INSERT}, {@code UPDATE} or {@code DELETE}. A view is read with
 *     the rights of whoever defined it: its tables are not checked again.
 * <li>Creating an object (inserting its metadata row) takes
 *     {@code CREATE TABLE}, {@code CREATE VIEW} and so on, on the schema;
 *     {@code CREATE SCHEMA} is held on the catalog. The user who creates
 *     an object is granted {@code OWNERSHIP} of it.
 * <li>Changing or dropping an object, or anything that belongs to it (a
 *     column, a partition, a firing, a refresh), takes ownership.
 * <li>Granting or revoking a privilege on an object takes ownership of it,
 *     or that privilege with the grant option.
 * <li>Roles, users and role membership take ownership of the catalog.
 * <li>Reading metadata is open to every user.
 * </ul>
 *
 * <p>Whoever the session acts as, dropping an object removes the grants on
 * it, and renaming it carries them along; otherwise they would apply to
 * whatever next took the name. */
final class Authorizer {
  static final String ALL = "ALL PRIVILEGES";
  private static final String CATALOG = "CATALOG";
  private static final String SCHEMA = "SCHEMA";
  /** Kinds of object that share the namespace of tables. */
  private static final Set<String> RELATIONS =
      Set.of("TABLE", "VIEW", "MATERIALIZED VIEW");

  static final String OWNERSHIP = "OWNERSHIP";

  /** What a metadata table's rows are, for the purposes of privileges.
   *
   * @param type Kind of object, as in {@link Grant#objectType()}
   * @param nameColumn Column that holds the object's name
   * @param part Whether a row is not the object itself but something that
   *     belongs to it, which only the object's owner may write */
  private record Kind(String type, String nameColumn, boolean part) {
  }

  private static final Map<String, Kind> KINDS =
      Map.of("TABLES", new Kind("TABLE", "TABLE_NAME", false),
          "VIEWS", new Kind("VIEW", "VIEW_NAME", false),
          "MATERIALIZED_VIEWS",
          new Kind("MATERIALIZED VIEW", "VIEW_NAME", false),
          "FUNCTIONS", new Kind("FUNCTION", "FUNCTION_NAME", false),
          "TRIGGERS", new Kind("TRIGGER", "TRIGGER_NAME", false),
          "SCHEMAS", new Kind(SCHEMA, null, false),
          "COLUMNS", new Kind("TABLE", "TABLE_NAME", true),
          "PARTITIONS", new Kind("TABLE", "TABLE_NAME", true),
          "FIRINGS", new Kind("TRIGGER", "TRIGGER_NAME", true),
          "REFRESHES", new Kind("MATERIALIZED VIEW", "VIEW_NAME", true));

  /** What is being done to a metadata row. */
  enum Op { INSERT, UPDATE, DELETE }

  private final String user;
  private final Set<String> groups;
  private final CatalogRegistry registry;

  Authorizer(Identity identity, CatalogRegistry registry) {
    this.user = identity == null ? null : identity.user();
    this.groups = identity == null ? Set.of() : identity.groups();
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
      if (applies(g.granteeType(), g.grantee(), roles)) {
        grants.add(g);
      }
    }
    return grants;
  }

  /** Whether a grantee is the user, one of the user's groups, or one of
   * the given roles. A grantee of no stated kind may be any of them. */
  private boolean applies(String type, String grantee, Set<String> roles) {
    final boolean any = type.equals(Grant.PRINCIPAL);
    return (any || type.equals("USER")) && grantee.equals(user)
        || (any || type.equals("GROUP")) && groups.contains(grantee)
        || (any || type.equals("ROLE")) && roles.contains(grantee);
  }

  /** The roles the user holds: directly, through a group, or through
   * other roles. */
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
        if (applies(g.granteeType(), g.grantee(), roles)) {
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

  /** Whether a grant confers a privilege on what it covers. */
  private static boolean includes(Grant g, String privilege) {
    if (g.privilege().equals(privilege) || g.privilege().equals(OWNERSHIP)) {
      return true;
    }
    // ALL PRIVILEGES on one object stops short of owning it.
    return g.privilege().equals(ALL)
        && (!privilege.equals(OWNERSHIP)
            || g.objectType().equals(CATALOG)
            || g.objectType().equals(SCHEMA));
  }

  private static boolean holds(List<Grant> grants, String privilege,
      boolean withGrantOption, String type, String schema, String name) {
    for (Grant g : grants) {
      if (includes(g, privilege)
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

  /** Checks that the user may write a row of a metadata table.
   *
   * @param table Name of the metadata table
   * @param row Returns the value of a column of the row, or null if the
   *     table has no such column */
  void checkWrite(String table, Op op, String catalog,
      UnaryOperator<String> row) {
    final List<Grant> grants = grants(catalog);
    if (grants == null) {
      return;
    }
    if (table.equals("GRANTS")) {
      checkGrant(grants, catalog, String.valueOf(row.apply("PRIVILEGE")),
          row.apply("OBJECT_TYPE"), row.apply("OBJECT_SCHEMA"),
          row.apply("OBJECT_NAME"));
      return;
    }
    final Kind kind = KINDS.get(table);
    final String schema = row.apply("SCHEMA_NAME");
    if (kind == null) {
      // Roles, users, and anything else without an owner of its own.
      require(grants, OWNERSHIP, SCHEMA, catalog, schema, null, table);
      return;
    }
    final String name =
        kind.nameColumn == null ? null : row.apply(kind.nameColumn);
    if (op != Op.INSERT || kind.part) {
      require(grants, OWNERSHIP, kind.type, catalog, schema, name, table);
    } else if (kind.type.equals(SCHEMA)) {
      require(grants, "CREATE SCHEMA", CATALOG, catalog, null, null, table);
    } else {
      require(grants, "CREATE " + kind.type, SCHEMA, catalog, schema, null,
          table);
    }
  }

  private void require(List<Grant> grants, String privilege, String type,
      String catalog, String schema, String name, String table) {
    if (!holds(grants, privilege, false, type, schema, name)) {
      throw new AccessDeniedException("User " + user + " lacks " + privilege
          + " on " + path(catalog, schema, name) + ", which changing METADATA."
          + table + " takes");
    }
  }

  /** Checks that the user may write a row of {@code GRANTS}: by owning the
   * object, or by holding the same privilege on it with the grant
   * option. */
  private void checkGrant(List<Grant> grants, String catalog,
      String privilege, String objectType, String objectSchema,
      String objectName) {
    if (!holds(grants, OWNERSHIP, false, objectType, objectSchema, objectName)
        && !holds(grants, privilege, true, objectType, objectSchema,
            objectName)) {
      throw new AccessDeniedException("User " + user + " may not grant or"
          + " revoke " + privilege + " on "
          + path(catalog, objectSchema, objectName) + ": that takes "
          + OWNERSHIP + " of it, or " + privilege + " WITH GRANT OPTION");
    }
  }

  /** Brings {@code GRANTS} into step with a row that has just been
   * written: the creator of an object owns it; the grants on an object go
   * when it does, and follow it when it is renamed.
   *
   * @param before The row deleted or updated, as for {@link #checkWrite};
   *     null for an insert
   * @param after The row inserted or updated to; null for a delete */
  void written(String table, String catalogName, UnaryOperator<String> before,
      UnaryOperator<String> after) {
    final Kind kind = KINDS.get(table);
    final Catalog catalog = registry.catalog(catalogName);
    if (kind == null || kind.part || catalog == null) {
      return;
    }
    final MetaTable<Grant> grants = catalog.grants();
    final List<Grant> all = new ArrayList<>();
    try {
      grants.scan().forEach(all::add);
    } catch (UnsupportedException e) {
      return; // not a catalog whose grants are ours to keep
    }
    final String schema = (after != null ? after : before).apply("SCHEMA_NAME");
    final String name = kind.nameColumn == null ? null
        : (after != null ? after : before).apply(kind.nameColumn);
    if (before == null) {
      if (user != null) {
        final Grant owner =
            new Grant(OWNERSHIP, kind.type, schema, name, "USER", user, true);
        if (grants.get(owner.key()).isEmpty()) {
          grants.insert(owner);
        }
      }
      return;
    }
    final String oldSchema = before.apply("SCHEMA_NAME");
    final String oldName =
        kind.nameColumn == null ? null : before.apply(kind.nameColumn);
    if (after != null && Objects.equals(oldSchema, schema)
        && Objects.equals(oldName, name)) {
      return;
    }
    for (Grant g : all) {
      if (!Objects.equals(g.objectSchema(), oldSchema)
          || !Objects.equals(g.objectName(), oldName)
          || !(g.objectType().equals(kind.type)
              || RELATIONS.contains(g.objectType())
                  && RELATIONS.contains(kind.type))) {
        continue;
      }
      grants.delete(g);
      if (after != null) {
        grants.insert(
            new Grant(g.privilege(), g.objectType(), schema, name,
                g.granteeType(), g.grantee(), g.grantable()));
      }
    }
  }

  /** Checks that the session may change which catalogs are mounted. */
  void checkCatalogs() {
    if (user != null) {
      throw new AccessDeniedException("User " + user + " may not create or"
          + " drop catalogs; only a session without a user may");
    }
  }
}
