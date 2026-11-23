/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.schema.impl;

import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.jdbc.CalcitePrepare;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.materialize.MaterializationKey;
import org.apache.calcite.materialize.MaterializationService;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.type.RelDataTypeImpl;
import org.apache.calcite.rel.type.RelProtoDataType;
import org.apache.calcite.schema.Schemas;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.TranslatableTable;

import io.monoptic.monoql.calcite.LegacyJdbc;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.lang.reflect.Type;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Table that is a materialized view.
 *
 * <p>It can exist in two states: materialized and not materialized. Over time,
 * a given materialized view may switch states. How it is expanded depends upon
 * its current state. State is managed by
 * {@link org.apache.calcite.materialize.MaterializationService}.
 */
public class MaterializedViewTable extends ViewTable {

  private final MaterializationKey key;

  public MaterializedViewTable(Type elementType,
      RelProtoDataType relDataType,
      String viewSql,
      List<String> viewSchemaPath,
      @Nullable List<String> viewPath,
      MaterializationKey key) {
    super(elementType, relDataType, viewSql, viewSchemaPath, viewPath);
    this.key = key;
  }

  /** Table macro that returns a materialized view. */
  public static MaterializedViewTableMacro create(final CalciteSchema schema,
      final String viewSql, final @Nullable List<String> viewSchemaPath, List<String> viewPath,
      final @Nullable String suggestedTableName, boolean existing) {
    return new MaterializedViewTableMacro(schema, viewSql, viewSchemaPath, viewPath,
        suggestedTableName, existing);
  }

  @Override public RelNode toRel(RelOptTable.ToRelContext context,
      RelOptTable relOptTable) {
    final CalciteSchema.TableEntry tableEntry =
        MaterializationService.instance().checkValid(key);
    if (tableEntry != null) {
      Table materializeTable = tableEntry.getTable();
      if (materializeTable instanceof TranslatableTable) {
        TranslatableTable table = (TranslatableTable) materializeTable;
        return table.toRel(context, relOptTable);
      }
    }
    return super.toRel(context, relOptTable);
  }

  /** Table function that returns the table that materializes a view. */
  public static class MaterializedViewTableMacro
      extends ViewTableMacro {
    private final MaterializationKey key;

    private MaterializedViewTableMacro(CalciteSchema schema, String viewSql,
        @Nullable List<String> viewSchemaPath, List<String> viewPath,
        @Nullable String suggestedTableName,
        boolean existing) {
      super(schema, viewSql,
          viewSchemaPath != null ? viewSchemaPath : schema.path(null), viewPath,
          Boolean.TRUE);
      this.key =
          requireNonNull(
              MaterializationService.instance()
                  .defineMaterialization(schema, null, viewSql, schemaPath,
                      suggestedTableName, true, existing));
    }

    @Override public TranslatableTable apply(List<? extends @Nullable Object> arguments) {
      assert arguments.isEmpty();
      CalcitePrepare.ParseResult parsed =
          Schemas.parse(LegacyJdbc.materializationConnection(), schema, schemaPath,
              viewSql);
      final List<String> schemaPath1 =
          schemaPath != null ? schemaPath : schema.path(null);
      final JavaTypeFactory typeFactory =
          LegacyJdbc.materializationConnection().getTypeFactory();
      return new MaterializedViewTable(typeFactory.getJavaClass(parsed.rowType),
          RelDataTypeImpl.proto(parsed.rowType), viewSql, schemaPath1, viewPath, key);
    }
  }
}
