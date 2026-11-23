/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql;

import io.monoptic.monoql.catalog.TableInfo;
import io.monoptic.monoql.catalog.memory.MemoryCatalog;

import org.apache.calcite.DataContext;
import org.apache.calcite.linq4j.AbstractEnumerable;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Enumerator;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.ScannableTable;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.sql.type.SqlTypeName;

import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import static io.monoptic.monoql.Sessions.run;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests that the rows of a query are read as the result is. */
class StreamingTest {
  /** A table of the integers from 1, without end. */
  static class Endless extends AbstractTable implements ScannableTable {
    int produced;
    int open;

    @Override public RelDataType getRowType(RelDataTypeFactory typeFactory) {
      return typeFactory.builder().add("N", SqlTypeName.INTEGER).build();
    }

    @Override public Enumerable<Object[]> scan(DataContext root) {
      return new AbstractEnumerable<>() {
        @Override public Enumerator<Object[]> enumerator() {
          ++open;
          return new Enumerator<>() {
            @Override public Object[] current() {
              return new Object[] {produced};
            }

            @Override public boolean moveNext() {
              ++produced;
              return true;
            }

            @Override public void reset() {
              throw new UnsupportedOperationException();
            }

            @Override public void close() {
              --open;
            }
          };
        }
      };
    }
  }

  private final Endless endless = new Endless();

  private Session session() {
    final MemoryCatalog catalog = new MemoryCatalog() {
      @Override public Optional<Table> bind(TableInfo table) {
        return table.tableName().equals("ENDLESS") ? Optional.of(endless)
            : super.bind(table);
      }
    };
    final Session session = Monoql.builder().catalog("C", catalog).build();
    run(session, "create schema s", "use c.s",
        "create table endless (n int)");
    return session;
  }

  @Test void rowsAreReadOnDemand() {
    try (Session session = session()) {
      try (Result result =
          session.execute("select n * 2 from endless where n > 10")) {
        final Iterator<Object[]> rows = result.iterator();
        assertArrayEquals(new Object[] {22}, rows.next());
        assertArrayEquals(new Object[] {24}, rows.next());
        assertTrue(endless.produced < 100, "read " + endless.produced);
        assertEquals(1, endless.open);
        // The rows can be read once.
        assertThrows(IllegalStateException.class, result::iterator);
        assertThrows(IllegalStateException.class, result::rows);
      }
      // Closing the result closed the scan.
      assertEquals(0, endless.open);
    }
  }

  @Test void readingTheLastRowCloses() {
    try (Session session = session()) {
      final Result result = session.execute("select n from endless limit 3");
      int count = 0;
      for (Object[] row : result) {
        assertArrayEquals(new Object[] {++count}, row);
      }
      assertEquals(3, count);
      assertEquals(0, endless.open);
    }
  }

  @Test void rowsCanBeLoaded() {
    try (Session session = Monoql.builder().build()) {
      run(session, "create table t (id int)", "insert into t values (1), (2)");
      final Result result = session.execute("select id from t order by id");
      assertEquals(2, result.rows().size());
      // Once loaded, they can be read as often as you like.
      assertEquals(2, result.rows().size());
      int count = 0;
      for (Object[] row : result) {
        assertArrayEquals(new Object[] {++count}, row);
      }
      assertEquals(2, count);
    }
  }

  @Test void scriptsLoadOrClose() {
    try (Session session = session()) {
      final List<Result> results =
          session.executeScript("select n from endless limit 2; show tables");
      assertEquals(2, results.get(0).rows().size());
      assertEquals(1, results.get(1).rows().size());
      assertEquals(0, endless.open);

      // A sink that does not read the rows does not leave the scan open.
      session.executeScript("select n from endless",
          result -> assertTrue(result.isQuery()));
      assertEquals(0, endless.open);
    }
  }

  @Test void dmlHasNoRows() {
    try (Session session = Monoql.builder().build()) {
      final Result result = session.execute("create table t (id int)");
      assertFalse(result.isQuery());
      assertFalse(result.iterator().hasNext());
      assertEquals(List.of(), result.rows());
    }
  }
}
