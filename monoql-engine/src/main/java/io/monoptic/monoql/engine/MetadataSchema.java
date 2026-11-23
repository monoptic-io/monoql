/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.engine;

import io.monoptic.monoql.catalog.Catalog;
import io.monoptic.monoql.catalog.CatalogInfo;
import io.monoptic.monoql.catalog.MetaRow;
import io.monoptic.monoql.catalog.MetaTables;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.schema.Table;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/** The {@code METADATA} schema: every metadata table of every mounted
 * catalog, plus the session's own tables.
 *
 * <p>This is the only way statements reach a catalog. There is no
 * {@code grant()} method anywhere; there is a {@code GRANTS} table. */
final class MetadataSchema extends LazySchema {
  static final String NAME = "METADATA";

  private final Map<String, MetadataTable> tables = new LinkedHashMap<>();

  /** Creates the schema.
   *
   * @param typeDeriver Validates a query against a schema path and returns
   *     its row type; used to find the columns of views */
  MetadataSchema(CatalogRegistry registry, Settings settings,
      BiFunction<String, List<String>, RelDataType> typeDeriver) {
    final Supplier<Map<String, Catalog>> catalogs = registry::catalogs;
    final Supplier<String> current = settings::catalog;
    add(Relation.ofSession(CatalogRegistry.NAME, CatalogInfo.class,
        registry));
    add(Relation.ofSession(Settings.NAME, Setting.class, settings));
    for (MetaTables.Def<?> def : MetaTables.ALL) {
      add(relation(def, catalogs, current));
      if (def == MetaTables.TABLES) {
        add(Relation.ofCatalogs(ColumnsTable.NAME, ColumnInfo.class,
            (name, c) -> new ColumnsTable(name, c, typeDeriver), catalogs,
            current));
      }
    }
  }

  private static <R extends MetaRow> Relation<R> relation(
      MetaTables.Def<R> def, Supplier<Map<String, Catalog>> catalogs,
      Supplier<String> current) {
    return Relation.ofCatalogs(def.name(), def.rowType(),
        (name, c) -> def.accessor().apply(c), catalogs, current);
  }

  private void add(Relation<?> relation) {
    tables.put(relation.name(), new MetadataTable(relation));
  }

  Relation<?> relation(String name) {
    return tables.get(name).relation;
  }

  @Override protected Table table(String name) {
    return tables.get(name);
  }

  @Override protected Set<String> tableNames() {
    return tables.keySet();
  }
}
