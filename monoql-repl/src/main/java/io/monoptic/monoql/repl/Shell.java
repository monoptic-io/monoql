/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.repl;

import io.monoptic.monoql.Result;
import io.monoptic.monoql.Session;

import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.lookup.LikePattern;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

/** The shell, minus the terminal: takes one input at a time (a
 * {@code !command}, or SQL), runs it against a {@link Session}, and prints
 * to a writer. The interactive loop, scripts and tests all drive this. */
final class Shell {
  /** A {@code !command}.
   *
   * @param name Name, without the {@code !}
   * @param args Synopsis of the arguments, for {@code !help}
   * @param help One line of help
   * @param sql If not null, the command is shorthand for this SQL; its
   *     arguments are appended */
  record Command(String name, String args, String help, String sql) {
  }

  static final List<Command> COMMANDS =
      List.of(
          new Command("help", "", "Show this help", null),
          new Command("quit", "", "Leave (also !exit, !q, or Ctrl-D)", null),
          new Command("catalogs", "", "List catalogs", "SHOW CATALOGS"),
          new Command("schemas", "[IN catalog]", "List schemas",
              "SHOW SCHEMAS"),
          new Command("tables", "[IN schema]", "List tables", "SHOW TABLES"),
          new Command("views", "[IN schema]", "List views", "SHOW VIEWS"),
          new Command("functions", "[IN schema]", "List functions",
              "SHOW FUNCTIONS"),
          new Command("triggers", "[IN schema]", "List triggers",
              "SHOW TRIGGERS"),
          new Command("roles", "[IN catalog]", "List roles", "SHOW ROLES"),
          new Command("users", "[IN catalog]", "List users", "SHOW USERS"),
          new Command("grants", "[ON object] [TO grantee]", "List grants",
              "SHOW GRANTS"),
          new Command("describe", "table", "Show the columns of a table",
              "DESCRIBE"),
          new Command("use", "[catalog.]schema",
              "Set the current catalog and schema", "USE"),
          new Command("settings", "", "Show session settings",
              "SHOW SETTINGS"),
          new Command("metadata", "",
              "List the tables of the METADATA schema, and their columns",
              null),
          new Command("lower", "statement",
              "Show the DML a statement is shorthand for, without running it",
              null),
          new Command("explain", "statement", "Show the plan of a statement",
              null),
          new Command("run", "file", "Run a script of statements and commands",
              null),
          new Command("format", "[table|vertical|csv|json]",
              "Show or set the output format", null),
          new Command("timing", "[on|off]", "Show or set timing of statements",
              null),
          new Command("verbose", "[on|off]",
              "Show or set printing of stack traces on error", null),
          new Command("history", "", "Show the statements entered so far",
              null),
          new Command("clear", "", "Clear the screen", null));

  private final Session session;
  private final PrintWriter out;
  private final Style style;
  private Formatter formatter = Formatter.TABLE;
  private boolean timing = true;
  private boolean verbose;
  private boolean failed;

  Shell(Session session, PrintWriter out, boolean color) {
    this.session = session;
    this.out = out;
    this.style = new Style(color);
  }

  Session session() {
    return session;
  }

  void format(Formatter formatter) {
    this.formatter = formatter;
  }

  void timing(boolean timing) {
    this.timing = timing;
  }

  /** Whether any input since the shell started has failed. */
  boolean failed() {
    return failed;
  }

  /** The prompt: the current catalog and schema. */
  String prompt() {
    final StringJoiner j = new StringJoiner(".");
    if (session.currentCatalog() != null) {
      j.add(session.currentCatalog());
      if (session.currentSchema() != null) {
        j.add(session.currentSchema());
      }
    }
    return (j.length() == 0 ? "monoql" : j.toString()) + "> ";
  }

  /** Runs one input.
   *
   * @return false if the shell should exit */
  boolean execute(String input) {
    final String trimmed = input.strip();
    try {
      if (trimmed.isEmpty()) {
        return true;
      }
      if (trimmed.startsWith("!")) {
        return command(trimmed.substring(1));
      }
      sql(trimmed);
    } catch (RuntimeException | StackOverflowError e) {
      error(e);
    } finally {
      out.flush();
    }
    return true;
  }

  private void error(Throwable e) {
    failed = true;
    String message = e.getMessage();
    if (message == null || message.isBlank()) {
      message = e.toString();
    }
    out.println(style.red("Error: ") + message);
    if (verbose) {
      e.printStackTrace(out);
    }
  }

  private void sql(String sql) {
    final long[] start = {System.nanoTime()};
    session.executeScript(sql, result -> {
      print(result, start[0]);
      start[0] = System.nanoTime();
    });
  }

  /** Prints a result. Rows are printed as they are read, so the time
   * reported is the time to produce and print them all. */
  private void print(Result result, long start) {
    final boolean chatty = formatter == Formatter.TABLE
        || formatter == Formatter.VERTICAL;
    final long n = result.isQuery() ? formatter.print(result, out, style)
        : result.updateCount();
    if (chatty) {
      final String elapsed = timing
          ? String.format(Locale.ROOT, " in %.3fs",
              (System.nanoTime() - start) / 1e9)
          : "";
      out.println(style.dim((result.isQuery() ? "" : "OK, ")
          + n + (n == 1 ? " row" : " rows")
          + (result.isQuery() ? "" : " affected") + elapsed));
    }
  }

  private boolean command(String line) {
    final int space = line.indexOf(' ');
    final String name =
        (space < 0 ? line : line.substring(0, space))
            .toLowerCase(Locale.ROOT);
    String args = space < 0 ? "" : line.substring(space + 1).strip();
    if (args.endsWith(";")) {
      args = args.substring(0, args.length() - 1).strip();
    }
    switch (name) {
    case "quit":
    case "exit":
    case "q":
      return false;
    case "help":
    case "?":
      help();
      return true;
    case "metadata":
      metadata();
      return true;
    case "lower":
      for (String sql : session.lower(requireArgs(name, args)).sql()) {
        out.println(highlight(sql) + ";");
      }
      return true;
    case "explain":
      out.print(session.explain(requireArgs(name, args)));
      return true;
    case "run":
      return run(Path.of(requireArgs(name, args)));
    case "format":
      if (!args.isEmpty()) {
        try {
          formatter = Formatter.of(args);
        } catch (IllegalArgumentException e) {
          throw new IllegalArgumentException("Unknown format '" + args
              + "'; expected table, vertical, csv or json");
        }
      }
      out.println("format is " + formatter.name().toLowerCase(Locale.ROOT));
      return true;
    case "timing":
      timing = flag(args, timing);
      out.println("timing is " + (timing ? "on" : "off"));
      return true;
    case "verbose":
      verbose = flag(args, verbose);
      out.println("verbose is " + (verbose ? "on" : "off"));
      return true;
    default:
      break;
    }
    for (Command command : COMMANDS) {
      if (command.name().equals(name) && command.sql() != null) {
        // Most commands are just SQL with fewer keystrokes.
        sql(args.isEmpty() ? command.sql() : command.sql() + " " + args);
        return true;
      }
    }
    throw new IllegalArgumentException("Unknown command !" + name
        + "; try !help");
  }

  private static String requireArgs(String name, String args) {
    if (args.isEmpty()) {
      throw new IllegalArgumentException("!" + name + " needs an argument; "
          + "try !help");
    }
    return args;
  }

  private static boolean flag(String args, boolean current) {
    switch (args.toLowerCase(Locale.ROOT)) {
    case "":
      return current;
    case "on":
    case "true":
      return true;
    case "off":
    case "false":
      return false;
    default:
      throw new IllegalArgumentException("Expected on or off");
    }
  }

  private String highlight(String sql) {
    return style.enabled() ? SqlHighlighter.highlight(sql).toAnsi() : sql;
  }

  private void help() {
    int width = 0;
    for (Command c : COMMANDS) {
      width = Math.max(width, c.name().length() + c.args().length() + 2);
    }
    for (Command c : COMMANDS) {
      final String usage = "!" + c.name() + " " + c.args();
      out.println("  " + style.cyan(usage)
          + " ".repeat(width - usage.length() + 2) + c.help());
    }
    out.println();
    out.println("Anything else is SQL, ended by a semicolon. Tab completes; "
        + "Ctrl-R searches history.");
  }

  private void metadata() {
    final SchemaPlus metadata = session.rootSchema().subSchemas().get("METADATA");
    for (String name
        : new java.util.TreeSet<>(metadata.tables().getNames(LikePattern.any()))) {
      final StringJoiner columns = new StringJoiner(", ");
      for (RelDataTypeField field
          : metadata.tables().get(name)
              .getRowType(new JavaTypeFactoryImpl()).getFieldList()) {
        columns.add(field.getName());
      }
      out.println("  " + style.bold(name) + " " + style.dim("(" + columns
          + ")"));
    }
  }

  /** Runs a script. Stops at the first failure. */
  boolean run(Path file) {
    final String script;
    try {
      script = Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalArgumentException("Cannot read " + file + ": " + e);
    }
    return run(script);
  }

  boolean run(String script) {
    final boolean failedBefore = failed;
    failed = false;
    try {
      for (String input : SqlLexer.split(script)) {
        if (!execute(input)) {
          return false;
        }
        if (failed) {
          break;
        }
      }
      return true;
    } finally {
      failed |= failedBefore;
    }
  }
}
