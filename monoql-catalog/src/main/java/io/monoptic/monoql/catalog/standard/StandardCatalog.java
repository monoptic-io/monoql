/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.catalog.standard;

import io.monoptic.monoql.catalog.Catalog;
import io.monoptic.monoql.catalog.CatalogException;
import io.monoptic.monoql.catalog.Firing;
import io.monoptic.monoql.catalog.FunctionInfo;
import io.monoptic.monoql.catalog.Grant;
import io.monoptic.monoql.catalog.Key;
import io.monoptic.monoql.catalog.MaterializedViewInfo;
import io.monoptic.monoql.catalog.MetaRow;
import io.monoptic.monoql.catalog.MetaTable;
import io.monoptic.monoql.catalog.MetaTables;
import io.monoptic.monoql.catalog.NotFoundException;
import io.monoptic.monoql.catalog.PartitionInfo;
import io.monoptic.monoql.catalog.Refresh;
import io.monoptic.monoql.catalog.RoleGrant;
import io.monoptic.monoql.catalog.RoleInfo;
import io.monoptic.monoql.catalog.SchemaInfo;
import io.monoptic.monoql.catalog.TableInfo;
import io.monoptic.monoql.catalog.TriggerInfo;
import io.monoptic.monoql.catalog.UserInfo;
import io.monoptic.monoql.catalog.ViewInfo;


import java.util.function.Supplier;

/** A catalog that supports everything, and enforces the integrity rules a
 * reasonable backend would: objects live in schemas that exist; tables,
 * views and materialized views share a namespace; a schema that still has
 * objects cannot be dropped; partitions belong to partitioned tables; role
 * memberships refer to roles.
 *
 * <p>Where the metadata is kept is up to its {@link Storage}, and where
 * the rows of its tables are, to the subclass: see {@link #bind},
 * {@link #tableChanged} and {@link #schemaChanged}. */
public abstract class StandardCatalog implements Catalog {
  private final Storage storage;
  private final StandardMetaTable<SchemaInfo> schemas;
  private final StandardMetaTable<TableInfo> tables;
  private final StandardMetaTable<ViewInfo> views;
  private final StandardMetaTable<MaterializedViewInfo> materializedViews;
  private final StandardMetaTable<Refresh> refreshes;
  private final StandardMetaTable<PartitionInfo> partitions;
  private final StandardMetaTable<FunctionInfo> functions;
  private final StandardMetaTable<TriggerInfo> triggers;
  private final StandardMetaTable<Firing> firings;
  private final StandardMetaTable<RoleInfo> roles;
  private final StandardMetaTable<UserInfo> users;
  private final StandardMetaTable<Grant> grants;
  private final StandardMetaTable<RoleGrant> roleGrants;

  protected StandardCatalog(Storage storage) {
    this.storage = storage;
    schemas =
        new StandardMetaTable<>(MetaTables.SCHEMAS.name(), storage,
            storage.rows(MetaTables.SCHEMAS)) {
          @Override protected void beforeDelete(SchemaInfo row) {
            final String schema = row.schemaName();
            for (MetaTable<? extends MetaRow> t
                : java.util.List.of(tables, views, materializedViews, functions,
                    triggers)) {
              for (MetaRow r : t.scan()) {
                if (schema.equals(r.key().parts().get(0))) {
                  throw new CatalogException("Schema " + schema
                      + " is not empty: " + t.name() + " contains " + r.key());
                }
              }
            }
          }

          @Override protected void afterChange(SchemaInfo before,
              SchemaInfo after) {
            schemaChanged(before, after);
          }
        };

    tables =
        new StandardMetaTable<>(MetaTables.TABLES.name(), storage,
            storage.rows(MetaTables.TABLES)) {
          @Override protected void beforeInsert(TableInfo row) {
            requireSchema(row.schemaName());
            requireFreeName(row.key());
            for (String k : row.primaryKey()) {
              requireColumn(row, k);
            }
            for (String k : row.partitionKeys()) {
              requireColumn(row, k);
            }
          }

          @Override protected void afterChange(TableInfo before,
              TableInfo after) {
            if (before != null) {
              for (PartitionInfo p : partitions.scan()) {
                if (p.schemaName().equals(before.schemaName())
                    && p.tableName().equals(before.tableName())) {
                  if (after == null) {
                    partitions.delete(p);
                  } else if (!after.key().equals(before.key())) {
                    partitions.update(p,
                        new PartitionInfo(after.schemaName(),
                            after.tableName(), p.spec(), p.options()));
                  }
                }
              }
            }
            tableChanged(before, after);
          }
        };

    views =
        new StandardMetaTable<>(MetaTables.VIEWS.name(), storage,
            storage.rows(MetaTables.VIEWS)) {
          @Override protected void beforeInsert(ViewInfo row) {
            requireSchema(row.schemaName());
            requireFreeName(row.key());
          }
        };

    materializedViews =
        new StandardMetaTable<>(MetaTables.MATERIALIZED_VIEWS.name(), storage,
            storage.rows(MetaTables.MATERIALIZED_VIEWS)) {
          @Override protected void beforeInsert(MaterializedViewInfo row) {
            requireSchema(row.schemaName());
            requireFreeName(row.key());
          }

          @Override protected void afterChange(MaterializedViewInfo before,
              MaterializedViewInfo after) {
            materializedViewChanged(before, after);
          }
        };

    refreshes =
        new StandardMetaTable<>(MetaTables.REFRESHES.name(), storage,
            storage.rows(MetaTables.REFRESHES)) {
          @Override protected void beforeInsert(Refresh row) {
            final Key key = Key.of(row.schemaName(), row.viewName());
            if (materializedViews.get(key).isEmpty()) {
              throw NotFoundException.of(materializedViews.name(), key);
            }
          }
        };

    partitions =
        new StandardMetaTable<>(MetaTables.PARTITIONS.name(), storage,
            storage.rows(MetaTables.PARTITIONS)) {
          @Override protected void beforeInsert(PartitionInfo row) {
            final Key key = Key.of(row.schemaName(), row.tableName());
            final TableInfo table = tables.get(key)
                .orElseThrow(() -> NotFoundException.of(tables.name(), key));
            if (!row.spec().keySet().equals(
                new java.util.LinkedHashSet<>(table.partitionKeys()))) {
              throw new CatalogException("Partition " + row.spec()
                  + " does not match the partition keys of " + key + ", "
                  + table.partitionKeys());
            }
          }
        };

    functions =
        new StandardMetaTable<>(MetaTables.FUNCTIONS.name(), storage,
            storage.rows(MetaTables.FUNCTIONS)) {
          @Override protected void beforeInsert(FunctionInfo row) {
            requireSchema(row.schemaName());
          }
        };

    triggers =
        new StandardMetaTable<>(MetaTables.TRIGGERS.name(), storage,
            storage.rows(MetaTables.TRIGGERS)) {
          @Override protected void beforeInsert(TriggerInfo row) {
            requireSchema(row.schemaName());
          }
        };

    firings =
        new StandardMetaTable<>(MetaTables.FIRINGS.name(), storage,
            storage.rows(MetaTables.FIRINGS)) {
          @Override protected void beforeInsert(Firing row) {
            final Key key = Key.of(row.schemaName(), row.triggerName());
            if (triggers.get(key).isEmpty()) {
              throw NotFoundException.of(triggers.name(), key);
            }
          }
        };

    roles =
        new StandardMetaTable<>(MetaTables.ROLES.name(), storage,
            storage.rows(MetaTables.ROLES));

    users =
        new StandardMetaTable<>(MetaTables.USERS.name(), storage,
            storage.rows(MetaTables.USERS));

    grants =
        new StandardMetaTable<>(MetaTables.GRANTS.name(), storage,
            storage.rows(MetaTables.GRANTS));

    roleGrants =
        new StandardMetaTable<>(MetaTables.ROLE_GRANTS.name(), storage,
            storage.rows(MetaTables.ROLE_GRANTS)) {
          @Override protected void beforeInsert(RoleGrant row) {
            final Key key = Key.of(row.roleName());
            if (roles.get(key).isEmpty()) {
              throw NotFoundException.of(roles.name(), key);
            }
          }
        };
  }

  /** Called, inside the change, when a schema has been created
   * ({@code before} is null), altered, or dropped ({@code after} is null);
   * throw to undo it. For a catalog that keeps schemas somewhere else as
   * well. */
  protected void schemaChanged(SchemaInfo before, SchemaInfo after) {
  }

  /** Called, inside the change, when a table has been created, altered
   * (renamed, perhaps, or given a column), or dropped; throw to undo it.
   * This is where a catalog makes the data plane follow. */
  protected void tableChanged(TableInfo before, TableInfo after) {
  }

  /** Called, inside the change, when a materialized view has been
   * created, altered (renamed, perhaps) or dropped; throw to undo it. For
   * a catalog that keeps the view's rows. */
  protected void materializedViewChanged(MaterializedViewInfo before,
      MaterializedViewInfo after) {
  }

  @Override public <T> T atomically(Supplier<T> work) {
    return storage.atomically(work);
  }

  @Override public void close() {
    storage.close();
  }

  private void requireSchema(String schemaName) {
    final Key key = Key.of(schemaName);
    if (schemas.get(key).isEmpty()) {
      throw NotFoundException.of(schemas.name(), key);
    }
  }

  private void requireFreeName(Key key) {
    for (MetaTable<?> t
        : java.util.List.of(tables, views, materializedViews)) {
      if (t.get(key).isPresent()) {
        throw new io.monoptic.monoql.catalog.AlreadyExistsException(
            t.name() + " already contains " + key);
      }
    }
  }

  private static void requireColumn(TableInfo table, String columnName) {
    if (table.columns().stream()
        .noneMatch(c -> c.columnName().equals(columnName))) {
      throw new CatalogException("Table " + table.key()
          + " has no column " + columnName);
    }
  }

  @Override public MetaTable<SchemaInfo> schemas() {
    return schemas;
  }

  @Override public MetaTable<TableInfo> tables() {
    return tables;
  }

  @Override public MetaTable<ViewInfo> views() {
    return views;
  }

  @Override public MetaTable<MaterializedViewInfo> materializedViews() {
    return materializedViews;
  }

  @Override public MetaTable<Refresh> refreshes() {
    return refreshes;
  }

  @Override public MetaTable<PartitionInfo> partitions() {
    return partitions;
  }

  @Override public MetaTable<FunctionInfo> functions() {
    return functions;
  }

  @Override public MetaTable<TriggerInfo> triggers() {
    return triggers;
  }

  @Override public MetaTable<Firing> firings() {
    return firings;
  }

  @Override public MetaTable<RoleInfo> roles() {
    return roles;
  }

  @Override public MetaTable<UserInfo> users() {
    return users;
  }

  @Override public MetaTable<Grant> grants() {
    return grants;
  }

  @Override public MetaTable<RoleGrant> roleGrants() {
    return roleGrants;
  }
}
