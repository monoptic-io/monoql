/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.catalog;

import java.util.List;
import java.util.function.Function;

/** The metadata tables of a {@link Catalog}, as data.
 *
 * <p>The engine builds the {@code metadata} schema from this list, so a table
 * added here is immediately queryable and writable. */
public final class MetaTables {
  private MetaTables() {
  }

  /** Description of one metadata table of a catalog.
   *
   * @param name Table name in the {@code metadata} schema
   * @param rowType Row record; its components are the columns
   * @param accessor Fetches the table from a catalog
   * @param <R> row type */
  public record Def<R extends MetaRow>(String name, Class<R> rowType,
      Function<Catalog, MetaTable<R>> accessor) {
    public MetaTable<R> of(Catalog catalog) {
      return accessor.apply(catalog);
    }
  }

  public static final Def<SchemaInfo> SCHEMAS =
      new Def<>("SCHEMAS", SchemaInfo.class, Catalog::schemas);
  public static final Def<TableInfo> TABLES =
      new Def<>("TABLES", TableInfo.class, Catalog::tables);
  public static final Def<ViewInfo> VIEWS =
      new Def<>("VIEWS", ViewInfo.class, Catalog::views);
  public static final Def<MaterializedViewInfo> MATERIALIZED_VIEWS =
      new Def<>("MATERIALIZED_VIEWS", MaterializedViewInfo.class,
          Catalog::materializedViews);
  public static final Def<PartitionInfo> PARTITIONS =
      new Def<>("PARTITIONS", PartitionInfo.class, Catalog::partitions);
  public static final Def<FunctionInfo> FUNCTIONS =
      new Def<>("FUNCTIONS", FunctionInfo.class, Catalog::functions);
  public static final Def<TriggerInfo> TRIGGERS =
      new Def<>("TRIGGERS", TriggerInfo.class, Catalog::triggers);
  public static final Def<Job> JOBS =
      new Def<>("JOBS", Job.class, Catalog::jobs);
  public static final Def<RoleInfo> ROLES =
      new Def<>("ROLES", RoleInfo.class, Catalog::roles);
  public static final Def<UserInfo> USERS =
      new Def<>("USERS", UserInfo.class, Catalog::users);
  public static final Def<Grant> GRANTS =
      new Def<>("GRANTS", Grant.class, Catalog::grants);
  public static final Def<RoleGrant> ROLE_GRANTS =
      new Def<>("ROLE_GRANTS", RoleGrant.class, Catalog::roleGrants);

  /** Every per-catalog metadata table. */
  public static final List<Def<?>> ALL =
      List.of(SCHEMAS, TABLES, VIEWS, MATERIALIZED_VIEWS, PARTITIONS,
          FUNCTIONS, TRIGGERS, JOBS, ROLES, USERS, GRANTS, ROLE_GRANTS);
}
