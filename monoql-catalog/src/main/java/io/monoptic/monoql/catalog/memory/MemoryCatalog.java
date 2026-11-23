/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.catalog.memory;

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

import org.apache.calcite.schema.Table;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** A catalog that lives on the heap and supports everything.
 *
 * <p>It enforces the integrity rules a reasonable backend would: objects
 * live in schemas that exist, tables, views and materialized views share a
 * namespace, a schema that still has objects cannot be dropped, partitions
 * belong to partitioned tables, role memberships refer to roles. */
public class MemoryCatalog implements Catalog {
  private final Map<Key, MemoryDataTable> data = new ConcurrentHashMap<>();

  private final MemoryMetaTable<SchemaInfo> schemas =
      new MemoryMetaTable<>(MetaTables.SCHEMAS.name()) {
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
      };

  private final MemoryMetaTable<TableInfo> tables =
      new MemoryMetaTable<>(MetaTables.TABLES.name()) {
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
          if (before == null) {
            return;
          }
          final MemoryDataTable store = data.remove(before.key());
          if (after != null && store != null) {
            store.alter(after.columns());
            data.put(after.key(), store);
          }
          for (PartitionInfo p : partitions.scan()) {
            if (p.schemaName().equals(before.schemaName())
                && p.tableName().equals(before.tableName())) {
              if (after == null) {
                partitions.delete(p);
              } else if (!after.key().equals(before.key())) {
                partitions.update(p,
                    new PartitionInfo(after.schemaName(), after.tableName(),
                        p.spec(), p.options()));
              }
            }
          }
        }
      };

  private final MemoryMetaTable<ViewInfo> views =
      new MemoryMetaTable<>(MetaTables.VIEWS.name()) {
        @Override protected void beforeInsert(ViewInfo row) {
          requireSchema(row.schemaName());
          requireFreeName(row.key());
        }
      };

  private final MemoryMetaTable<MaterializedViewInfo> materializedViews =
      new MemoryMetaTable<>(MetaTables.MATERIALIZED_VIEWS.name()) {
        @Override protected void beforeInsert(MaterializedViewInfo row) {
          requireSchema(row.schemaName());
          requireFreeName(row.key());
        }
      };

  private final MemoryMetaTable<Refresh> refreshes =
      new MemoryMetaTable<>(MetaTables.REFRESHES.name()) {
        @Override protected void beforeInsert(Refresh row) {
          final Key key = Key.of(row.schemaName(), row.viewName());
          if (materializedViews.get(key).isEmpty()) {
            throw NotFoundException.of(materializedViews.name(), key);
          }
        }
      };

  private final MemoryMetaTable<PartitionInfo> partitions =
      new MemoryMetaTable<>(MetaTables.PARTITIONS.name()) {
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

  private final MemoryMetaTable<FunctionInfo> functions =
      new MemoryMetaTable<>(MetaTables.FUNCTIONS.name()) {
        @Override protected void beforeInsert(FunctionInfo row) {
          requireSchema(row.schemaName());
        }
      };

  private final MemoryMetaTable<TriggerInfo> triggers =
      new MemoryMetaTable<>(MetaTables.TRIGGERS.name()) {
        @Override protected void beforeInsert(TriggerInfo row) {
          requireSchema(row.schemaName());
        }
      };

  private final MemoryMetaTable<Firing> firings =
      new MemoryMetaTable<>(MetaTables.FIRINGS.name()) {
        @Override protected void beforeInsert(Firing row) {
          final Key key = Key.of(row.schemaName(), row.triggerName());
          if (triggers.get(key).isEmpty()) {
            throw NotFoundException.of(triggers.name(), key);
          }
        }
      };

  private final MemoryMetaTable<RoleInfo> roles =
      new MemoryMetaTable<>(MetaTables.ROLES.name());

  private final MemoryMetaTable<UserInfo> users =
      new MemoryMetaTable<>(MetaTables.USERS.name());

  private final MemoryMetaTable<Grant> grants =
      new MemoryMetaTable<>(MetaTables.GRANTS.name());

  private final MemoryMetaTable<RoleGrant> roleGrants =
      new MemoryMetaTable<>(MetaTables.ROLE_GRANTS.name()) {
        @Override protected void beforeInsert(RoleGrant row) {
          final Key key = Key.of(row.roleName());
          if (roles.get(key).isEmpty()) {
            throw NotFoundException.of(roles.name(), key);
          }
        }
      };

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

  @Override public Optional<Table> bind(TableInfo table) {
    return Optional.of(
        data.computeIfAbsent(table.key(),
            k -> new MemoryDataTable(table.columns())));
  }
}
