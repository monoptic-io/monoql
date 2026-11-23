/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.engine;

import org.apache.calcite.DataContext;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Linq4j;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.ScannableTable;
import org.apache.calcite.schema.impl.AbstractTable;

/** A table of the {@code METADATA} schema, as the planner sees it.
 *
 * <p>Queries scan it like any other table. DML against it is not planned as
 * a physical operator; the session computes the affected rows and applies
 * them through the {@link Relation}. */
final class MetadataTable extends AbstractTable implements ScannableTable {
  final Relation<?> relation;

  MetadataTable(Relation<?> relation) {
    this.relation = relation;
  }

  @Override public RelDataType getRowType(RelDataTypeFactory typeFactory) {
    return relation.rowType(typeFactory);
  }

  @Override public Enumerable<Object[]> scan(DataContext root) {
    return Linq4j.asEnumerable(relation.scan());
  }
}
