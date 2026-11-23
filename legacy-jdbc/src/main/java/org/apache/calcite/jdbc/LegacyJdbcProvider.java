/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.apache.calcite.jdbc;

import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.schema.Schema;

import io.monoptic.monoql.calcite.LegacyJdbc;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.SQLException;
import java.util.Properties;

/** Offers the legacy driver to the parts of core that still ask for a
 * connection. It sits in this package, rather than under io.monoptic,
 * because it needs the package-private side of the driver. */
public class LegacyJdbcProvider implements LegacyJdbc.Provider {
  @Override public CalciteConnection connect(
      @Nullable CalciteSchema rootSchema,
      @Nullable JavaTypeFactory typeFactory) {
    if (rootSchema != null) {
      return CalciteMetaImpl.connect(rootSchema, typeFactory);
    }
    try {
      return new Driver().connect("jdbc:calcite:", new Properties())
          .unwrap(CalciteConnection.class);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  @Override public Schema metadataSchema() {
    return MetadataSchema.INSTANCE;
  }
}
