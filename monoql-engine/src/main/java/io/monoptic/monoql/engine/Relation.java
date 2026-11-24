/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.engine;

import io.monoptic.monoql.catalog.Catalog;
import io.monoptic.monoql.catalog.CatalogException;
import io.monoptic.monoql.catalog.MetaRow;
import io.monoptic.monoql.catalog.MetaTable;
import io.monoptic.monoql.catalog.MetaTables;
import io.monoptic.monoql.catalog.NotFoundException;
import io.monoptic.monoql.catalog.UnsupportedException;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/** One table of the {@code METADATA} schema, in terms of {@code Object[]}
 * rows: the bridge between SQL and the typed {@link MetaTable}s of the
 * catalog API.
 *
 * <p>There are two flavors. A session relation wraps a single
 * {@link MetaTable} owned by the session ({@code CATALOGS},
 * {@code SETTINGS}). A catalog relation is the union of the same
 * {@link MetaTable} across every mounted catalog; its rows start with
 * {@code CATALOG_NAME}, and writes are routed to the catalog that column
 * names (or to the current catalog, if it is null).
 *
 * @param <R> row type */
final class Relation<R extends MetaRow> {
  private final String name;
  private final RowCodec<R> codec;
  /** For a session relation, the table; otherwise null. */
  private final MetaTable<R> sessionTable;
  /** For a catalog relation, fetches the table from a catalog, given the
   * name it is mounted under. */
  private final BiFunction<String, Catalog, MetaTable<R>> accessor;
  private final Supplier<Map<String, Catalog>> catalogs;
  private final Supplier<String> currentCatalog;

  private Relation(String name, Class<R> rowType, MetaTable<R> sessionTable,
      BiFunction<String, Catalog, MetaTable<R>> accessor,
      Supplier<Map<String, Catalog>> catalogs,
      Supplier<String> currentCatalog) {
    this.name = name;
    this.codec = RowCodec.of(rowType, sessionTable == null);
    this.sessionTable = sessionTable;
    this.accessor = accessor;
    this.catalogs = catalogs;
    this.currentCatalog = currentCatalog;
  }

  static <R extends MetaRow> Relation<R> ofSession(String name,
      Class<R> rowType, MetaTable<R> table) {
    return new Relation<>(name, rowType, table, null, null, null);
  }

  static <R extends MetaRow> Relation<R> ofCatalogs(String name,
      Class<R> rowType,
      BiFunction<String, Catalog, MetaTable<R>> accessor,
      Supplier<Map<String, Catalog>> catalogs,
      Supplier<String> currentCatalog) {
    return new Relation<>(name, rowType, null, accessor, catalogs,
        currentCatalog);
  }

  String name() {
    return name;
  }

  RowCodec<R> codec() {
    return codec;
  }

  RelDataType rowType(RelDataTypeFactory typeFactory) {
    return codec.rowType(typeFactory);
  }

  /** Reads every row. In a catalog relation, catalogs that do not support
   * this table contribute no rows. */
  List<Object[]> scan() {
    final List<Object[]> rows = new ArrayList<>();
    if (sessionTable != null) {
      for (R row : sessionTable.scan()) {
        rows.add(codec.encode(null, row));
      }
      return rows;
    }
    for (Map.Entry<String, Catalog> e : catalogs.get().entrySet()) {
      final Iterable<R> scan;
      try {
        scan = accessor.apply(e.getKey(), e.getValue()).scan();
      } catch (UnsupportedException ignored) {
        continue;
      }
      for (R row : scan) {
        rows.add(codec.encode(e.getKey(), row));
      }
    }
    return rows;
  }

  /** Checks that a catalog exists and can at least read this table.
   *
   * <p>Scans across catalogs skip the ones that lack a table. That is right
   * for a query, but when a statement is aimed at one catalog and finds
   * nothing, "not supported" is a better answer than "not found". */
  void checkSupported(String catalogName) {
    if (sessionTable != null) {
      return;
    }
    final Catalog catalog = catalogs.get().get(catalogName);
    if (catalog == null) {
      throw new NotFoundException("Catalog " + catalogName + " not found");
    }
    accessor.apply(catalogName, catalog).scan();
  }

  /** Builds a row from named values; columns not named are null. */
  Object[] row(Map<String, Object> values) {
    final Object[] row = new Object[codec.columnNames().size()];
    values.forEach((column, value) -> row[codec.ordinal(column)] = value);
    return row;
  }

  /** Returns the catalog a row of a catalog relation belongs to: the one
   * it names, or the current one. */
  String catalogName(Object[] row) {
    String catalogName = codec.catalogOf(row);
    if (catalogName == null) {
      catalogName = currentCatalog.get();
      if (catalogName == null) {
        throw new CatalogException(RowCodec.CATALOG_NAME
            + " is required, because there is no current catalog");
      }
    }
    return catalogName;
  }

  String value(Object[] row, String column) {
    final Object o = row[codec.ordinal(column)];
    return o == null ? null : o.toString();
  }

  /** Returns a row as a function from column name to value; null for a
   * column this relation does not have. */
  private UnaryOperator<String> values(Object[] row) {
    return column -> codec.columnNames().contains(column)
        ? value(row, column) : null;
  }

  /** Checks that the user of the session may write a row. */
  private void authorize(Authorizer authorizer, Authorizer.Op op,
      Object[] row) {
    if (sessionTable != null) {
      // Anyone may change their own settings.
      if (authorizer.enabled() && name.equals(CatalogRegistry.NAME)) {
        authorizer.checkCatalogs();
      }
    } else if (authorizer.enabled()) {
      authorizer.checkWrite(name, op, catalogName(row), values(row));
    }
  }

  /** Tells the authorizer that a row has been written, so that it can keep
   * the grants in step. */
  private void written(Authorizer authorizer, Object[] before,
      Object[] after) {
    if (sessionTable == null) {
      authorizer.written(name, catalogName(before != null ? before : after),
          before == null ? null : values(before),
          after == null ? null : values(after));
    }
  }

  /** Returns the table that a row should be written to. */
  private MetaTable<R> target(Object[] row) {
    if (sessionTable != null) {
      return sessionTable;
    }
    final String catalogName = catalogName(row);
    final Catalog catalog = catalogs.get().get(catalogName);
    if (catalog == null) {
      throw new NotFoundException("Catalog " + catalogName + " not found");
    }
    return accessor.apply(catalogName, catalog);
  }

  private R decode(Object[] row) {
    try {
      return codec.decode(row);
    } catch (IllegalArgumentException e) {
      throw new CatalogException("Invalid row for " + name + ": "
          + e.getMessage(), e);
    }
  }

  void insert(Authorizer authorizer, Object[] row) {
    authorize(authorizer, Authorizer.Op.INSERT, row);
    target(row).insert(decode(row));
    written(authorizer, null, row);
  }

  /** Inserts a row, or replaces the row with the same key. */
  void upsert(Authorizer authorizer, Object[] row) {
    final MetaTable<R> table = target(row);
    final R after = decode(row);
    final Optional<R> before = table.get(after.key());
    if (before.isPresent()) {
      authorize(authorizer, Authorizer.Op.UPDATE, row);
      table.update(before.get(), after);
    } else {
      authorize(authorizer, Authorizer.Op.INSERT, row);
      table.insert(after);
      written(authorizer, null, row);
    }
  }

  void update(Authorizer authorizer, Object[] before, Object[] after) {
    final MetaTable<R> table = target(before);
    if (sessionTable == null
        && after[0] != null && !after[0].equals(before[0])) {
      throw new CatalogException(
          "Cannot move a row of " + name + " to another catalog");
    }
    final R old = decode(before);
    final R updated = decode(after);
    authorize(authorizer, Authorizer.Op.UPDATE, before);
    if (!old.key().equals(updated.key())) {
      // A rename, perhaps into another schema: the row arrives somewhere.
      authorize(authorizer, Authorizer.Op.INSERT, after);
    }
    table.update(old, updated);
    written(authorizer, before, after);
  }

  void delete(Authorizer authorizer, Object[] row) {
    authorize(authorizer, Authorizer.Op.DELETE, row);
    target(row).delete(decode(row));
    written(authorizer, row, null);
  }
}
