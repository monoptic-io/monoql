/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.catalog;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeSystem;
import org.apache.calcite.sql.type.SqlTypeFactoryImpl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests {@link DataTypes}. */
class DataTypesTest {
  private final RelDataTypeFactory typeFactory =
      new SqlTypeFactoryImpl(RelDataTypeSystem.DEFAULT);

  @ParameterizedTest
  @ValueSource(strings = {
      "INTEGER", "BIGINT", "BOOLEAN", "DOUBLE", "VARCHAR", "VARCHAR(20)",
      "CHAR(3)", "DECIMAL(10, 2)", "DECIMAL(5)", "DATE", "TIMESTAMP(3)",
      "TIMESTAMP(0)", "TIMESTAMP(3) WITH LOCAL TIME ZONE", "VARBINARY(8)",
      "INTEGER ARRAY", "VARCHAR(5) ARRAY ARRAY", "MAP<VARCHAR, INTEGER>",
      "ROW(\"A\" INTEGER, \"B\" VARCHAR(3) ARRAY)"})
  void roundTrip(String type) {
    final RelDataType parsed = DataTypes.parse(type, true, typeFactory);
    assertEquals(type, DataTypes.format(parsed));
    assertEquals(parsed,
        DataTypes.parse(DataTypes.format(parsed), true, typeFactory));
  }

  @Test void nullabilityAndSynonyms() {
    assertTrue(DataTypes.parse("INT", true, typeFactory).isNullable());
    assertFalse(DataTypes.parse("INT", false, typeFactory).isNullable());
    assertEquals("INTEGER",
        DataTypes.format(DataTypes.parse("int", true, typeFactory)));
    assertThrows(CatalogException.class,
        () -> DataTypes.parse("NOT A TYPE", true, typeFactory));
  }

  @Test void rowTypes() {
    final List<Column> columns =
        List.of(new Column("ID", "BIGINT", false, null),
            new Column("NAME", "VARCHAR(10)", true, null));
    final RelDataType rowType = DataTypes.rowType(columns, typeFactory);
    assertEquals("RecordType(BIGINT NOT NULL ID, VARCHAR(10) NAME) NOT NULL",
        rowType.getFullTypeString());
    assertEquals(columns, DataTypes.columns(rowType));
  }
}
