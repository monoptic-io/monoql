/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.repl;

import io.monoptic.monoql.Identity;
import io.monoptic.monoql.Monoql;
import io.monoptic.monoql.Session;

import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.EOFError;
import org.jline.reader.EndOfFileException;
import org.jline.reader.History;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.ParsedLine;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.InfoCmp;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** The monoql shell.
 *
 * <pre>
 * monoql                      interactive
 * monoql -e "select 1"        run a statement and exit
 * monoql -f script.sql        run a script and exit
 * monoql &lt; script.sql         likewise
 * </pre> */
public final class Repl {
  private Repl() {
  }

  private static final String USAGE = String.join("\n",
      "Usage: monoql [options]",
      "",
      "  -e, --execute SQL     Run SQL (or a !command) and exit; repeatable",
      "  -f, --file FILE       Run a script and exit; repeatable",
      "  -i, --init FILE       Run a script before starting",
      "                        (default: ~/.monoql/init.sql, if it exists)",
      "  -j, --jar PATH        Add a jar to the class path: a JDBC driver for a",
      "                        jdbc catalog, or a catalog provider; repeatable",
      "  -u, --user NAME       Act as this user, subject to grants, once the",
      "                        init script has run; see !as",
      "  -g, --group NAME      A group the user is in; repeatable",
      "      --format FORMAT   table (default), vertical, csv or json",
      "      --no-color        Plain output",
      "      --no-timing       Do not print how long statements take",
      "  -h, --help            Show this help",
      "",
      "With no -e or -f, starts an interactive shell; or, if standard input",
      "is not a terminal, runs it as a script.");

  public static void main(String[] args) throws IOException {
    final List<String[]> work = new ArrayList<>(); // {"e"|"f", argument}
    final Path home = Path.of(System.getProperty("user.home"), ".monoql");
    Path init = home.resolve("init.sql");
    boolean explicitInit = false;
    Formatter formatter = null;
    boolean color = System.getenv("NO_COLOR") == null;
    boolean timing = true;
    final List<Path> jars = new ArrayList<>();
    String user = null;
    final Set<String> groups = new LinkedHashSet<>();
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
      case "-e":
      case "--execute":
        work.add(new String[] {"e", argument(args, ++i)});
        break;
      case "-f":
      case "--file":
        work.add(new String[] {"f", argument(args, ++i)});
        break;
      case "-i":
      case "--init":
        init = Path.of(argument(args, ++i));
        explicitInit = true;
        break;
      case "-j":
      case "--jar":
        jars.add(Path.of(argument(args, ++i)));
        break;
      case "-u":
      case "--user":
        user = name(argument(args, ++i));
        break;
      case "-g":
      case "--group":
        groups.add(name(argument(args, ++i)));
        break;
      case "--format":
        try {
          formatter = Formatter.of(argument(args, ++i));
        } catch (IllegalArgumentException e) {
          usage("Unknown format " + args[i]);
        }
        break;
      case "--no-color":
        color = false;
        break;
      case "--no-timing":
        timing = false;
        break;
      case "-h":
      case "--help":
        System.out.println(USAGE);
        return;
      default:
        usage("Unknown option " + args[i]);
      }
    }

    if (!jars.isEmpty()) {
      // Catalog providers are discovered, and JDBC drivers loaded, through
      // the context class loader; so this is all it takes to add either.
      final URL[] urls = new URL[jars.size()];
      for (int i = 0; i < urls.length; i++) {
        if (!Files.isReadable(jars.get(i))) {
          usage("Cannot read " + jars.get(i));
        }
        urls[i] = jars.get(i).toUri().toURL();
      }
      Thread.currentThread().setContextClassLoader(
          new URLClassLoader(urls, Repl.class.getClassLoader()));
    }

    if (user == null && !groups.isEmpty()) {
      usage("--group needs --user");
    }
    final Identity identity = user == null ? null : new Identity(user, groups);
    final boolean interactive = work.isEmpty() && System.console() != null;
    try (Session session = Monoql.builder().build()) {
      if (!interactive) {
        final PrintWriter out =
            new PrintWriter(System.out, true, StandardCharsets.UTF_8);
        final Shell shell =
            new Shell(session, out, color && System.console() != null);
        shell.timing(false);
        if (formatter != null) {
          shell.format(formatter);
        }
        init(shell, init, explicitInit);
        if (identity != null) {
          shell.as(identity);
        }
        if (work.isEmpty()) {
          shell.run(new String(System.in.readAllBytes(),
              StandardCharsets.UTF_8));
        }
        for (String[] w : work) {
          if (shell.failed()) {
            break;
          }
          if (w[0].equals("f")) {
            shell.run(Path.of(w[1]));
          } else {
            shell.run(w[1].strip().startsWith("!") || w[1].strip().endsWith(";")
                ? w[1] : w[1] + ";");
          }
        }
        out.flush();
        if (shell.failed()) {
          System.exit(1);
        }
        return;
      }
      interact(session, home, init, explicitInit, identity, formatter, color,
          timing);
    }
  }

  /** Reads a name as {@code !as} does: upper case unless quoted. */
  private static String name(String argument) {
    final List<String> names = Shell.names(argument);
    if (names.size() != 1) {
      usage("Expected one name, not '" + argument + "'");
    }
    return names.get(0);
  }

  private static String argument(String[] args, int i) {
    if (i >= args.length) {
      usage(args[i - 1] + " needs an argument");
    }
    return args[i];
  }

  private static void usage(String problem) {
    System.err.println(problem);
    System.err.println(USAGE);
    System.exit(2);
  }

  private static void init(Shell shell, Path init, boolean explicit) {
    if (explicit || Files.isReadable(init)) {
      shell.run(init);
    }
  }

  private static void interact(Session session, Path home, Path init,
      boolean explicitInit, Identity identity, Formatter formatter,
      boolean color, boolean timing) throws IOException {
    try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
      final Shell shell = new Shell(session, terminal.writer(), color);
      shell.timing(timing);
      if (formatter != null) {
        shell.format(formatter);
      }
      // Ctrl-C while a statement runs stops the statement. (While a line is
      // being edited, the line reader has its own handler.)
      terminal.handle(Terminal.Signal.INT, signal -> shell.cancel());
      final Completions completions = new Completions(shell::session);
      final Completer completer = (reader, line, candidates) -> {
        final String word = line.word().substring(0, line.wordCursor());
        for (Completions.Candidate c
            : completions.complete(line.line().substring(0, line.cursor()),
                word)) {
          // No trailing space after a name: the next thing typed is as
          // likely to be a dot as a space.
          candidates.add(
              new Candidate(c.value(), c.value(), c.kind(), null, null, null,
                  !c.kind().equals("catalog") && !c.kind().equals("schema")));
        }
      };
      Files.createDirectories(home);
      final LineReader reader =
          LineReaderBuilder.builder()
              .terminal(terminal)
              .appName("monoql")
              .parser(new StatementParser())
              .highlighter(color ? new SqlHighlighter() : null)
              .completer(completer)
              .variable(LineReader.HISTORY_FILE, home.resolve("history"))
              .variable(LineReader.SECONDARY_PROMPT_PATTERN, "%P -> ")
              // '!' starts a command here, not a history expansion.
              .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
              .option(LineReader.Option.CASE_INSENSITIVE, true)
              .option(LineReader.Option.AUTO_FRESH_LINE, true)
              .build();

      terminal.writer().println(
          banner(color) + "  Type !help for commands; end SQL with a "
              + "semicolon.");
      init(shell, init, explicitInit);
      if (identity != null) {
        shell.as(identity);
      }
      while (true) {
        final String prompt = color
            ? new AttributedStringBuilder()
                .style(AttributedStyle.BOLD.foreground(AttributedStyle.CYAN))
                .append(shell.prompt())
                .toAnsi(terminal)
            : shell.prompt();
        final String line;
        try {
          line = reader.readLine(prompt);
        } catch (UserInterruptException e) {
          continue; // Ctrl-C abandons the line, not the shell
        } catch (EndOfFileException e) {
          break; // Ctrl-D
        }
        final String command = line.strip();
        if (command.equals("!history")) {
          for (History.Entry entry : reader.getHistory()) {
            terminal.writer().println(
                String.format("%5d  %s", entry.index() + 1, entry.line()));
          }
        } else if (command.equals("!clear")) {
          terminal.puts(InfoCmp.Capability.clear_screen);
          terminal.flush();
        } else if (!shell.execute(line)) {
          break;
        }
      }
    }
  }

  private static String banner(boolean color) {
    final String version = Repl.class.getPackage().getImplementationVersion();
    final String name = "monoql" + (version == null ? "" : " " + version);
    return color ? "\u001b[1m" + name + "\u001b[0m" : name;
  }

  /** Tells the line reader when to keep reading: SQL continues onto the
   * next line until it reaches a semicolon. */
  private static final class StatementParser extends DefaultParser {
    StatementParser() {
      // Backslash is not an escape in SQL, and quotes are the highlighter's
      // business.
      setEscapeChars(null);
      setQuoteChars(null);
    }

    @Override public ParsedLine parse(String line, int cursor,
        ParseContext context) {
      if (context == ParseContext.ACCEPT_LINE && !SqlLexer.isComplete(line)) {
        throw new EOFError(-1, -1, "statement is not finished");
      }
      return super.parse(line, cursor, context);
    }
  }
}
