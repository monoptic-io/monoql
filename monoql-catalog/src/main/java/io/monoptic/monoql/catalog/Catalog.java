/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.catalog;

import org.apache.calcite.schema.Table;

import java.util.Optional;

/** A catalog: a set of metadata tables, and optionally the data behind them.
 *
 * <p>Every method has a default that supports nothing, so an implementation
 * is exactly as large as what its backend can do. A catalog for a system that
 * only understands ACLs implements {@link #grants()} and stops there.
 *
 * <p>Implementations are created by a {@link CatalogProvider}. */
public interface Catalog extends AutoCloseable {
  /** Namespaces. {@code CREATE SCHEMA}, {@code DROP SCHEMA}. */
  default MetaTable<SchemaInfo> schemas() {
    return MetaTable.unsupported(MetaTables.SCHEMAS.name());
  }

  /** Tables and their columns. {@code CREATE TABLE}, {@code ALTER TABLE},
   * {@code DROP TABLE}. */
  default MetaTable<TableInfo> tables() {
    return MetaTable.unsupported(MetaTables.TABLES.name());
  }

  /** Views. {@code CREATE VIEW}, {@code DROP VIEW}. */
  default MetaTable<ViewInfo> views() {
    return MetaTable.unsupported(MetaTables.VIEWS.name());
  }

  /** Materialized views. {@code CREATE MATERIALIZED VIEW}, {@code PAUSE} and
   * {@code RESUME MATERIALIZED VIEW}, {@code DROP MATERIALIZED VIEW}. */
  default MetaTable<MaterializedViewInfo> materializedViews() {
    return MetaTable.unsupported(MetaTables.MATERIALIZED_VIEWS.name());
  }

  /** Requests to refresh a materialized view.
   * {@code REFRESH MATERIALIZED VIEW}. */
  default MetaTable<Refresh> refreshes() {
    return MetaTable.unsupported(MetaTables.REFRESHES.name());
  }

  /** Partitions of tables. {@code ALTER TABLE ... ADD PARTITION},
   * {@code ALTER TABLE ... DROP PARTITION}. */
  default MetaTable<PartitionInfo> partitions() {
    return MetaTable.unsupported(MetaTables.PARTITIONS.name());
  }

  /** User-defined functions. {@code CREATE FUNCTION}, {@code DROP FUNCTION}. */
  default MetaTable<FunctionInfo> functions() {
    return MetaTable.unsupported(MetaTables.FUNCTIONS.name());
  }

  /** Triggers and scheduled tasks. {@code CREATE TRIGGER}, {@code PAUSE} and
   * {@code RESUME TRIGGER}, {@code DROP TRIGGER}. */
  default MetaTable<TriggerInfo> triggers() {
    return MetaTable.unsupported(MetaTables.TRIGGERS.name());
  }

  /** Requests to run a trigger. {@code FIRE TRIGGER}. */
  default MetaTable<Firing> firings() {
    return MetaTable.unsupported(MetaTables.FIRINGS.name());
  }

  /** Roles. {@code CREATE ROLE}, {@code DROP ROLE}. */
  default MetaTable<RoleInfo> roles() {
    return MetaTable.unsupported(MetaTables.ROLES.name());
  }

  /** Users. {@code CREATE USER}, {@code DROP USER}. */
  default MetaTable<UserInfo> users() {
    return MetaTable.unsupported(MetaTables.USERS.name());
  }

  /** Privileges on objects. {@code GRANT ... ON}, {@code REVOKE ... ON}. */
  default MetaTable<Grant> grants() {
    return MetaTable.unsupported(MetaTables.GRANTS.name());
  }

  /** Role memberships. {@code GRANT ROLE}, {@code REVOKE ROLE}. */
  default MetaTable<RoleGrant> roleGrants() {
    return MetaTable.unsupported(MetaTables.ROLE_GRANTS.name());
  }

  /** The data plane: returns something that can read (and perhaps write)
   * the rows of a table.
   *
   * <p>By default a table is metadata only: it can be described, granted on,
   * and planned against, but a query that needs its rows fails. */
  default Optional<Table> bind(TableInfo table) {
    return Optional.empty();
  }

  /** Releases resources. Called when the catalog is dropped from a session,
   * or the session closes. */
  @Override default void close() {
  }
}
