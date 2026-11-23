/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.engine;

import io.monoptic.monoql.catalog.DataTypes;
import io.monoptic.monoql.catalog.TableInfo;
import io.monoptic.monoql.catalog.UnsupportedException;

import org.apache.calcite.DataContext;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.ScannableTable;
import org.apache.calcite.schema.impl.AbstractTable;

/** A table whose catalog knows its shape but does not provide its rows.
 * It can be described, validated against and planned, but not read. */
final class MetadataOnlyTable extends AbstractTable implements ScannableTable {
  private final String catalogName;
  private final TableInfo info;

  MetadataOnlyTable(String catalogName, TableInfo info) {
    this.catalogName = catalogName;
    this.info = info;
  }

  @Override public RelDataType getRowType(RelDataTypeFactory typeFactory) {
    return DataTypes.rowType(info.columns(), typeFactory);
  }

  @Override public Enumerable<Object[]> scan(DataContext root) {
    throw new UnsupportedException("Catalog " + catalogName
        + " does not provide the rows of table " + info.key());
  }
}
