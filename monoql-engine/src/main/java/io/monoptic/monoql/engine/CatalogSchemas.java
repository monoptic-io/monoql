/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.engine;

import io.monoptic.monoql.MonoqlException;
import io.monoptic.monoql.catalog.Catalog;
import io.monoptic.monoql.catalog.CatalogException;
import io.monoptic.monoql.catalog.Column;
import io.monoptic.monoql.catalog.DataTypes;
import io.monoptic.monoql.catalog.FunctionInfo;
import io.monoptic.monoql.catalog.Key;
import io.monoptic.monoql.catalog.MaterializedViewInfo;
import io.monoptic.monoql.catalog.MetaRow;
import io.monoptic.monoql.catalog.MetaTable;
import io.monoptic.monoql.catalog.SchemaInfo;
import io.monoptic.monoql.catalog.TableInfo;
import io.monoptic.monoql.catalog.UnsupportedException;
import io.monoptic.monoql.catalog.ViewInfo;

import org.apache.calcite.DataContext;
import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.linq4j.tree.Expressions;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelProtoDataType;
import org.apache.calcite.schema.Function;
import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.impl.AggregateFunctionImpl;
import org.apache.calcite.schema.impl.ScalarFunctionImpl;
import org.apache.calcite.schema.impl.TableFunctionImpl;
import org.apache.calcite.util.BuiltInMethod;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;

/** The Calcite schema tree, backed directly by catalogs: the root holds
 * {@code METADATA} and one schema per mounted catalog; a catalog holds its
 * schemas; a schema holds tables, views and functions. */
final class CatalogSchemas {
  private CatalogSchemas() {
  }

  /** Looks up a row, treating an unsupported table as empty. */
  private static <R extends MetaRow> Optional<R> get(MetaTable<R> table,
      Key key) {
    try {
      return table.get(key);
    } catch (UnsupportedException e) {
      return Optional.empty();
    }
  }

  /** Returns what holds the rows of a materialized view, if its catalog
   * keeps them. If not, or if its definition is no longer valid, the view
   * reads as its definition (and in the second case, fails as it would). */
  static Optional<Table> stored(Catalog catalog, MaterializedViewInfo view,
      ViewTable definition) {
    final List<Column> columns;
    try {
      columns = DataTypes.columns(definition.rowType());
    } catch (MonoqlException | CatalogException e) {
      return Optional.empty();
    }
    return catalog.bind(view, columns);
  }

  /** Adds the names (key part {@code part}) of rows in a schema. */
  private static void names(Set<String> names, MetaTable<?> table,
      String schemaName) {
    try {
      for (MetaRow row : table.scan()) {
        final List<Object> parts = row.key().parts();
        if (schemaName == null) {
          names.add((String) parts.get(0));
        } else if (schemaName.equals(parts.get(0))) {
          names.add((String) parts.get(1));
        }
      }
    } catch (UnsupportedException e) {
      // nothing to add
    }
  }

  /** The root of the tree. */
  static final class Root extends LazySchema {
    private final CatalogRegistry registry;
    private final MetadataSchema metadata;
    private final BiFunction<String, List<String>, RelDataType> typeDeriver;

    Root(CatalogRegistry registry, MetadataSchema metadata,
        BiFunction<String, List<String>, RelDataType> typeDeriver) {
      this.registry = registry;
      this.metadata = metadata;
      this.typeDeriver = typeDeriver;
    }

    @Override protected Schema subSchema(String name) {
      if (name.equals(MetadataSchema.NAME)) {
        return metadata;
      }
      final Catalog catalog = registry.catalog(name);
      return catalog == null ? null
          : new CatalogSchema(name, catalog, typeDeriver);
    }

    @Override protected Set<String> subSchemaNames() {
      final Set<String> names = new TreeSet<>(registry.catalogs().keySet());
      names.add(MetadataSchema.NAME);
      return names;
    }

    // A type's name in another dialect is known everywhere, in a column's
    // declaration and in a CAST alike.
    @Override public RelProtoDataType getType(String name) {
      return DataTypes.synonym(name);
    }

    @Override public Set<String> getTypeNames() {
      return DataTypes.SYNONYMS.keySet();
    }

    @Override public Expression getExpression(SchemaPlus parentSchema,
        String name) {
      // The root has no parent; generated code reaches it from the context.
      return Expressions.call(DataContext.ROOT,
          BuiltInMethod.DATA_CONTEXT_GET_ROOT_SCHEMA.method);
    }
  }

  /** A catalog. */
  static final class CatalogSchema extends LazySchema {
    private final String catalogName;
    private final Catalog catalog;
    private final BiFunction<String, List<String>, RelDataType> typeDeriver;

    CatalogSchema(String catalogName, Catalog catalog,
        BiFunction<String, List<String>, RelDataType> typeDeriver) {
      this.catalogName = catalogName;
      this.catalog = catalog;
      this.typeDeriver = typeDeriver;
    }

    @Override protected Schema subSchema(String name) {
      final Optional<SchemaInfo> info = get(catalog.schemas(), Key.of(name));
      return info.isEmpty() ? null
          : new Namespace(catalogName, catalog, name, typeDeriver);
    }

    @Override protected Set<String> subSchemaNames() {
      final Set<String> names = new TreeSet<>();
      names(names, catalog.schemas(), null);
      return names;
    }
  }

  /** A schema within a catalog. */
  static final class Namespace extends LazySchema {
    private final String catalogName;
    private final Catalog catalog;
    private final String schemaName;
    private final BiFunction<String, List<String>, RelDataType> typeDeriver;

    Namespace(String catalogName, Catalog catalog, String schemaName,
        BiFunction<String, List<String>, RelDataType> typeDeriver) {
      this.catalogName = catalogName;
      this.catalog = catalog;
      this.schemaName = schemaName;
      this.typeDeriver = typeDeriver;
    }

    @Override protected Table table(String name) {
      final Key key = Key.of(schemaName, name);
      final Optional<TableInfo> table = get(catalog.tables(), key);
      if (table.isPresent()) {
        return catalog.bind(table.get())
            .orElseGet(() -> new MetadataOnlyTable(catalogName, table.get()));
      }
      final List<String> schemaPath = List.of(catalogName, schemaName);
      final List<String> viewPath = List.of(catalogName, schemaName, name);
      final Optional<ViewInfo> view = get(catalog.views(), key);
      if (view.isPresent()) {
        return new ViewTable(view.get().definition(),
            view.get().columnNames(), schemaPath, viewPath, typeDeriver);
      }
      final Optional<MaterializedViewInfo> mv =
          get(catalog.materializedViews(), key);
      if (mv.isPresent()) {
        final ViewTable definition =
            new ViewTable(mv.get().definition(), mv.get().columnNames(),
                schemaPath, viewPath, typeDeriver);
        return stored(catalog, mv.get(), definition)
            .orElse(definition);
      }
      return null;
    }

    @Override protected Set<String> tableNames() {
      final Set<String> names = new TreeSet<>();
      names(names, catalog.tables(), schemaName);
      names(names, catalog.views(), schemaName);
      names(names, catalog.materializedViews(), schemaName);
      return names;
    }

    @Override public Collection<Function> getFunctions(String name) {
      final Optional<FunctionInfo> info =
          get(catalog.functions(), Key.of(schemaName, name));
      if (info.isEmpty()
          || !info.get().functionLanguage().equalsIgnoreCase("JAVA")) {
        // Functions in other languages belong to the backend; the engine
        // cannot call them.
        return List.of();
      }
      return List.of(javaFunction(info.get()));
    }

    @Override public Set<String> getFunctionNames() {
      final Set<String> names = new TreeSet<>();
      names(names, catalog.functions(), schemaName);
      return names;
    }
  }

  /** Loads a Java function: {@code com.example.MyClass} (a scalar function
   * with an {@code eval} method, a table function, or an aggregate), or
   * {@code com.example.MyClass#method}. */
  static Function javaFunction(FunctionInfo info) {
    final String definition = info.definition();
    final int hash = definition.indexOf('#');
    final String className =
        hash < 0 ? definition : definition.substring(0, hash);
    final Class<?> clazz;
    try {
      clazz = Class.forName(className, true,
          Thread.currentThread().getContextClassLoader());
    } catch (ClassNotFoundException e) {
      throw new CatalogException("Function " + info.key() + ": class "
          + className + " not found", e);
    }
    Function f;
    if (hash >= 0) {
      f = ScalarFunctionImpl.create(clazz, definition.substring(hash + 1));
    } else {
      f = ScalarFunctionImpl.create(clazz, "eval");
      if (f == null) {
        f = TableFunctionImpl.create(clazz);
      }
      if (f == null) {
        f = AggregateFunctionImpl.create(clazz);
      }
    }
    if (f == null) {
      throw new CatalogException("Function " + info.key() + ": "
          + definition + " is not a scalar, table or aggregate function");
    }
    return f;
  }
}
