/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.repl;

import io.monoptic.monoql.Monoql;
import io.monoptic.monoql.Session;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests the shell without a terminal. */
class ShellTest {
  private Session session;
  private StringWriter buffer;
  private Shell shell;

  @BeforeEach void open() {
    session = Monoql.builder().build();
    buffer = new StringWriter();
    shell = new Shell(session, new PrintWriter(buffer), false);
    shell.timing(false);
  }

  @AfterEach void close() {
    session.close();
  }

  /** Runs an input and returns what it printed. */
  private String run(String input) {
    buffer.getBuffer().setLength(0);
    assertTrue(shell.execute(input));
    return buffer.toString();
  }

  @Test void sqlAndFormats() {
    assertEquals("OK, 1 row affected\n",
        run("create table t (id int, name varchar);"));
    assertEquals("OK, 2 rows affected\n",
        run("insert into t values (1, 'a,b'), (2, null);"));
    assertEquals("┌────┬──────┐\n"
            + "│ ID │ NAME │\n"
            + "├────┼──────┤\n"
            + "│  1 │ a,b  │\n"
            + "│  2 │ NULL │\n"
            + "└────┴──────┘\n"
            + "2 rows\n",
        run("select * from t order by id;"));

    assertEquals("format is csv\n", run("!format csv"));
    assertEquals("ID,NAME\n1,\"a,b\"\n2,\n", run("select * from t order by id;"));
    run("!format json");
    assertEquals("[\n"
            + "  {\"ID\": 1, \"NAME\": \"a,b\"},\n"
            + "  {\"ID\": 2, \"NAME\": null}\n"
            + "]\n",
        run("select * from t order by id;"));
    run("!format vertical");
    assertEquals("── row 1 ──\n  ID │ 2\nNAME │ NULL\n1 row\n",
        run("select * from t where id = 2;"));
    assertTrue(run("!format nonsense").startsWith("Error: Unknown format"));
    assertTrue(shell.failed());
  }

  @Test void severalStatementsInOneInput() {
    assertEquals("OK, 1 row affected\nOK, 1 row affected\n"
            + "┌────────┐\n│ EXPR$0 │\n├────────┤\n│      1 │\n└────────┘\n"
            + "1 row\n",
        run("create table t (a int); insert into t values (1);\n"
            + "select count(*) from t;"));
  }

  @Test void commands() {
    run("create table t (id int);");
    assertTrue(run("!tables").contains("│ T "));
    assertTrue(run("!describe t").contains("│ ID "));
    assertTrue(run("!catalogs").contains("MEMORY"));
    assertTrue(run("!schemas").contains("MAIN"));
    assertTrue(run("!settings").contains("catalog"));
    assertTrue(run("!help").contains("!lower statement"));
    assertTrue(run("!metadata").contains("GRANTS (CATALOG_NAME, PRIVILEGE,"));
    assertEquals("timing is off\n", run("!timing"));
    assertEquals("verbose is on\n", run("!verbose on"));
    assertTrue(run("!nosuch").startsWith("Error: Unknown command !nosuch"));
    assertTrue(run("!lower").startsWith("Error: !lower needs an argument"));

    // !lower shows the DML, and does not run it.
    assertEquals("DELETE FROM \"METADATA\".\"TABLES\"\n"
            + "WHERE \"CATALOG_NAME\" = 'MEMORY' AND \"SCHEMA_NAME\" = 'MAIN' "
            + "AND \"TABLE_NAME\" = 'T';\n",
        run("!lower drop table t"));
    assertTrue(run("!tables").contains("│ T "));
    assertTrue(run("!explain select * from t")
        .contains("EnumerableTableScan"));

    // Commands that are SQL take the rest of the line.
    run("create schema s; create table s.u (a int);");
    assertTrue(run("!tables in s").contains("│ U "));
    run("!use s");
    assertEquals("MEMORY.S> ", shell.prompt());
    run("grant select on u to role r;");
    assertTrue(run("!grants on u").contains("SELECT"));

    buffer.getBuffer().setLength(0);
    assertFalse(shell.execute("!quit"));
    assertFalse(shell.execute("!q"));
  }

  @Test void errorsDoNotEndTheShell() {
    assertTrue(run("select * from nosuch;")
        .startsWith("Error: From line 1, column 15"));
    assertTrue(shell.failed());
    assertTrue(run("drop table nosuch;")
        .startsWith("Error: Table MEMORY.MAIN.NOSUCH not found"));
    assertEquals("", run("   "));
  }

  @Test void scripts(@TempDir Path dir) throws IOException {
    final Path script = dir.resolve("s.sql");
    Files.writeString(script, "-- a script\n"
        + "create table t (a int,\n  b varchar);\n"
        + "!format csv\n"
        + "insert into t values (1, 'semi;colon');\n\n"
        + "select * from t;\n"
        + "select * from nosuch;\n"
        + "select 'not reached';\n");
    final String output = run("!run " + script);
    assertTrue(output.contains("A,B\n1,semi;colon\n"), output);
    assertTrue(output.contains("Error:"), output);
    assertFalse(output.contains("not reached"), output);
    assertTrue(run("!run " + dir.resolve("missing.sql"))
        .startsWith("Error: Cannot read"));
  }

  @Test void lexer() {
    assertTrue(SqlLexer.isComplete(""));
    assertTrue(SqlLexer.isComplete("!tables"));
    assertTrue(SqlLexer.isComplete("select 1;"));
    assertTrue(SqlLexer.isComplete("select 1; -- done"));
    assertTrue(SqlLexer.isComplete("-- just a comment"));
    assertFalse(SqlLexer.isComplete("select 1"));
    assertFalse(SqlLexer.isComplete("select ';"));
    assertFalse(SqlLexer.isComplete("select 1 /* ;"));
    assertFalse(SqlLexer.isComplete("select \"a;"));
    assertTrue(SqlLexer.isComplete("select 'it''s; fine';"));
    assertEquals(
        List.of("select 1;", "!format csv", "select\n  ';'\n;", "select 2"),
        SqlLexer.split("select 1;\n!format csv\nselect\n  ';'\n;\n\nselect 2"));
  }

  @Test void highlighting() {
    final String sql = "select 'x', 12, \"Id\" from t -- note";
    final String ansi = SqlHighlighter.highlight(sql).toAnsi();
    // Styling never changes the text.
    assertEquals(sql, ansi.replaceAll("\u001b\\[[0-9;]*m", ""));
    assertTrue(ansi.contains("\u001b["), ansi);
    assertEquals("!lower grant select on t to r",
        SqlHighlighter.highlight("!lower grant select on t to r").toString());
    assertEquals(Keywords.Weight.STRONG, Keywords.weight("select"));
    assertEquals(Keywords.Weight.SOFT, Keywords.weight("pause"));
    assertEquals(Keywords.Weight.NONE, Keywords.weight("customers"));
  }

  @Test void completion() {
    run("create table customers (id int, full_name varchar);");
    run("create table \"Mixed Case\" (a int);");
    final Completions completions = new Completions(() -> session);
    assertEquals(List.of("!tables", "!triggers", "!timing"),
        values(completions, "!t", "!t"));
    // Names are offered in the case being typed, and quoted if need be.
    assertEquals(List.of("customers"), values(completions, "select * from cu",
        "cu").stream().filter(v -> v.startsWith("cu") && !v.equals("cube")
        && !v.startsWith("cur") && !v.startsWith("cum")).toList());
    assertTrue(values(completions, "select * from CU", "CU")
        .contains("CUSTOMERS"));
    assertTrue(values(completions, "select * from \"Mi", "\"Mi")
        .contains("\"Mixed Case\""));
    // Paths: catalogs, schemas, tables, columns.
    assertTrue(values(completions, "select * from me", "me")
        .containsAll(List.of("memory", "metadata")));
    assertEquals(List.of("memory.main"),
        values(completions, "use memory.", "memory."));
    assertTrue(values(completions, "from metadata.gr", "metadata.gr")
        .contains("metadata.grants"));
    assertEquals(List.of("customers.id", "customers.full_name"),
        values(completions, "select customers.", "customers."));
    assertEquals(List.of("memory.main.customers.full_name"),
        values(completions, "select memory.main.customers.f",
            "memory.main.customers.f"));
    // Columns of the tables a statement mentions; and keywords.
    final List<String> afterWhere =
        values(completions, "select * from customers where f", "f");
    assertTrue(afterWhere.contains("full_name"), afterWhere.toString());
    assertTrue(afterWhere.contains("from"), afterWhere.toString());
    assertTrue(values(completions, "pa", "pa").contains("pause"));
    assertTrue(values(completions, "SEL", "SEL").contains("SELECT"));
  }

  /** {@code !as} switches who the shell acts as, over the same
   * catalogs. */
  @Test void actingAs() {
    run("create table t (id int); insert into t values (1);");
    assertEquals("MEMORY.MAIN> ", shell.prompt());
    assertEquals("Acting as user ANN, in ANALYSTS; not authenticated\n",
        run("!as ann analysts"));
    assertEquals("ANN@MEMORY.MAIN> ", shell.prompt());
    assertEquals("Error: User ANN lacks SELECT on MEMORY.MAIN.T\n",
        run("select * from t;"));
    assertTrue(run("!settings").contains("│ groups       │ ANALYSTS"));
    assertEquals("Acting as nobody; nothing is checked\n", run("!as"));
    run("grant select on t to group analysts;");
    run("!as ann analysts");
    assertTrue(run("select * from t;").contains("1 row"));
    // Quoted names are taken as they are; and one user is not another.
    assertEquals("Acting as user bob@example.com; not authenticated\n",
        run("!as 'bob@example.com'"));
    assertEquals("bob@example.com@MEMORY.MAIN> ", shell.prompt());
    assertTrue(run("select * from t;").startsWith("Error: User bob@"));
    run("!as");
    assertTrue(run("select * from t;").contains("1 row"));
  }

  /** A long result is printed a batch at a time, each batch a table of
   * its own. */
  @Test void longResults() {
    run("create table t (id int, name varchar);");
    final StringBuilder insert = new StringBuilder("insert into t values ");
    for (int i = 1; i <= Formatter.BATCH; i++) {
      insert.append('(').append(i).append(", 'a'), ");
    }
    run(insert + "(" + (Formatter.BATCH + 1) + ", 'longer');");
    final String[] lines = run("select * from t order by id;").split("\n");
    final int second = Formatter.BATCH + 4;
    assertEquals("│ 1000 │ a    │", lines[second - 2]);
    assertEquals("└──────┴──────┘", lines[second - 1]);
    assertEquals("┌──────┬────────┐", lines[second]);
    assertEquals("│ ID   │ NAME   │", lines[second + 1]);
    assertEquals("│ 1001 │ longer │", lines[second + 3]);
    assertEquals("1001 rows", lines[lines.length - 1]);

    // Exactly one batch: no empty table after it.
    final String[] exact =
        run("select * from t where id <= 1000;").split("\n");
    assertEquals(Formatter.BATCH + 5, exact.length);

    shell.format(Formatter.JSON);
    final String json = run("select * from t where id > 999 order by id;");
    assertEquals("[\n"
        + "  {\"ID\": 1000, \"NAME\": \"a\"},\n"
        + "  {\"ID\": 1001, \"NAME\": \"longer\"}\n"
        + "]\n", json);
  }

  private static List<String> values(Completions completions, String buffer,
      String word) {
    return completions.complete(buffer, word).stream()
        .map(Completions.Candidate::value).toList();
  }
}
