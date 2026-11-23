/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.catalog;

import io.monoptic.monoql.catalog.memory.MemoryCatalog;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests {@link MemoryCatalog} directly, through the SPI. */
class MemoryCatalogTest {
  private static TableInfo table(String schema, String name) {
    return new TableInfo(schema, name,
        List.of(new Column("A", "INTEGER", true, null)), List.of(),
        List.of("A"), null, Map.of());
  }

  @Test void integrity() {
    final MemoryCatalog catalog = new MemoryCatalog();
    // Objects need a schema.
    assertThrows(NotFoundException.class,
        () -> catalog.tables().insert(table("S", "T")));
    catalog.schemas().insert(new SchemaInfo("S", null, null));
    catalog.tables().insert(table("S", "T"));
    assertThrows(AlreadyExistsException.class,
        () -> catalog.tables().insert(table("S", "T")));
    // Tables and views share a namespace.
    assertThrows(AlreadyExistsException.class,
        () -> catalog.views().insert(
            new ViewInfo("S", "T", null, "select 1", null)));
    // Keys must be columns.
    assertThrows(CatalogException.class,
        () -> catalog.tables().insert(
            new TableInfo("S", "U", List.of(), List.of("NOPE"), List.of(),
                null, null)));
    // A schema with objects in it stays.
    assertThrows(CatalogException.class,
        () -> catalog.schemas().delete(new SchemaInfo("S", null, null)));

    // Partitions follow their table.
    catalog.partitions().insert(
        new PartitionInfo("S", "T", Map.of("A", "1"), null));
    catalog.tables().update(table("S", "T"), table("S", "U"));
    assertEquals(Key.of("S", "U", Map.of("A", "1")),
        catalog.partitions().scan().iterator().next().key());
    catalog.tables().delete(table("S", "U"));
    assertTrue(!catalog.partitions().scan().iterator().hasNext());
    catalog.schemas().delete(new SchemaInfo("S", null, null));
    assertThrows(NotFoundException.class,
        () -> catalog.schemas().delete(new SchemaInfo("S", null, null)));
  }

  @Test void rowsValidateThemselves() {
    assertThrows(IllegalArgumentException.class,
        () -> new TableInfo(null, "T", null, null, null, null, null));
    assertThrows(IllegalArgumentException.class,
        () -> new Grant("SELECT", "TABLE", "S", "T", null, "R", false));
    // Collections are never null, and are immutable.
    final SchemaInfo schema = new SchemaInfo("S", null, null);
    assertEquals(Map.of(), schema.options());
    assertThrows(UnsupportedOperationException.class,
        () -> schema.options().put("k", "v"));
    // Grantability is not part of a grant's identity.
    assertEquals(
        new Grant("SELECT", "TABLE", "S", "T", "ROLE", "R", false).key(),
        new Grant("SELECT", "TABLE", "S", "T", "ROLE", "R", true).key());
  }
}
