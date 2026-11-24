/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.engine;

import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.TranslatableTable;
import org.apache.calcite.schema.impl.AbstractTable;

import java.util.List;
import java.util.function.BiFunction;

/** A view (or a materialized view whose catalog does not keep its rows): expands
 * to its definition when referenced. */
final class ViewTable extends AbstractTable implements TranslatableTable {
  private final String sql;
  private final List<String> schemaPath;
  private final List<String> viewPath;
  private final BiFunction<String, List<String>, RelDataType> typeDeriver;

  /** Creates a ViewTable.
   *
   * @param definition The query
   * @param columnNames Column aliases, or empty
   * @param schemaPath Catalog and schema of the view; unqualified names in
   *     the definition resolve here
   * @param viewPath Full name of the view
   * @param typeDeriver Validates a query against a schema path and returns
   *     its row type */
  ViewTable(String definition, List<String> columnNames,
      List<String> schemaPath, List<String> viewPath,
      BiFunction<String, List<String>, RelDataType> typeDeriver) {
    if (columnNames.isEmpty()) {
      this.sql = definition;
    } else {
      final StringBuilder b = new StringBuilder("SELECT * FROM (")
          .append(definition).append(") AS ")
          .append(SqlText.id(viewPath.get(viewPath.size() - 1))).append(" (");
      for (int i = 0; i < columnNames.size(); i++) {
        b.append(i > 0 ? ", " : "").append(SqlText.id(columnNames.get(i)));
      }
      this.sql = b.append(')').toString();
    }
    this.schemaPath = schemaPath;
    this.viewPath = viewPath;
    this.typeDeriver = typeDeriver;
  }

  /** The query, with the view's column names applied. */
  String sql() {
    return sql;
  }

  /** Derives the row type by validating the definition; fails if it is
   * no longer valid. */
  RelDataType rowType() {
    return typeDeriver.apply(sql, schemaPath);
  }

  @Override public RelDataType getRowType(RelDataTypeFactory typeFactory) {
    return typeFactory.copyType(rowType());
  }

  @Override public RelNode toRel(RelOptTable.ToRelContext context,
      RelOptTable relOptTable) {
    return context.expandView(relOptTable.getRowType(), sql, schemaPath,
        viewPath).rel;
  }
}
