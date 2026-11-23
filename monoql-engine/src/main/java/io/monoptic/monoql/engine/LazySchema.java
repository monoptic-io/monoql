/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.engine;

import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.rel.type.RelProtoDataType;
import org.apache.calcite.schema.Function;
import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.SchemaVersion;
import org.apache.calcite.schema.Schemas;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.lookup.CompatibilityLookup;
import org.apache.calcite.schema.lookup.Lookup;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/** Base class for schemas whose contents are looked up on demand, never
 * cached: the catalog is the source of truth, and it can change under us. */
abstract class LazySchema implements Schema {
  /** Returns a table, or null. */
  protected Table table(String name) {
    return null;
  }

  protected Set<String> tableNames() {
    return Set.of();
  }

  /** Returns a sub-schema, or null. */
  protected Schema subSchema(String name) {
    return null;
  }

  protected Set<String> subSchemaNames() {
    return Set.of();
  }

  @Override public Lookup<Table> tables() {
    return new CompatibilityLookup<>(this::table, this::tableNames);
  }

  @Override public Lookup<? extends Schema> subSchemas() {
    return new CompatibilityLookup<>(this::subSchema, this::subSchemaNames);
  }

  @SuppressWarnings("deprecation")
  @Override public Table getTable(String name) {
    return table(name);
  }

  @SuppressWarnings("deprecation")
  @Override public Set<String> getTableNames() {
    return tableNames();
  }

  @SuppressWarnings("deprecation")
  @Override public Schema getSubSchema(String name) {
    return subSchema(name);
  }

  @SuppressWarnings("deprecation")
  @Override public Set<String> getSubSchemaNames() {
    return subSchemaNames();
  }

  @Override public RelProtoDataType getType(String name) {
    return null;
  }

  @Override public Set<String> getTypeNames() {
    return Set.of();
  }

  @Override public Collection<Function> getFunctions(String name) {
    return List.of();
  }

  @Override public Set<String> getFunctionNames() {
    return Set.of();
  }

  @Override public Expression getExpression(SchemaPlus parentSchema,
      String name) {
    return Schemas.subSchemaExpression(
        java.util.Objects.requireNonNull(parentSchema, "parentSchema"), name,
        getClass());
  }

  @Override public boolean isMutable() {
    return true;
  }

  @Override public Schema snapshot(SchemaVersion version) {
    return this;
  }
}
