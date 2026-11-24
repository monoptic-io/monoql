/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.catalog;

import org.apache.calcite.schema.Table;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

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

  /** Returns something that holds the rows of a materialized view: what
   * its definition returned when it was last refreshed. The catalog makes
   * it if it is not there yet, with these columns, and reshapes it if the
   * columns have changed.
   *
   * <p>If what is returned is a {@link WritableTable}, the engine fills it
   * when the view is created, and again at each {@code REFRESH}, with
   * {@link WritableTable#replace}. Otherwise refreshing it is the
   * catalog's business, and a {@code REFRESH} is a row in
   * {@link #refreshes} for it to act on.
   *
   * <p>By default a materialized view has no rows of its own, and reads
   * as its definition: never stale, and never faster than the query.
   *
   * @param columns What the view's definition returns today */
  default Optional<Table> bind(MaterializedViewInfo view,
      List<Column> columns) {
    return Optional.empty();
  }

  /** Runs some work as a unit: if it throws, the changes it made to this
   * catalog are undone, and until it returns, nobody else sees them. The
   * engine runs each statement that changes something inside this, for
   * every catalog of the session; so that a statement which is several
   * changes (a table and its grants, say) happens entirely or not at all.
   *
   * <p>Work is run on the calling thread, and may itself call this.
   *
   * <p>By default the work is just run: a catalog that cannot undo does
   * not pretend to, and a statement that fails part of the way through has
   * happened part of the way. */
  default <T> T atomically(Supplier<T> work) {
    return work.get();
  }

  /** Releases resources. Called when the catalog is dropped from a session,
   * or the session closes. */
  @Override default void close() {
  }
}
