/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.repl;

import io.monoptic.monoql.Result;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Prints query results. */
enum Formatter {
  /** A boxed table. Long values are truncated; use {@link #VERTICAL} to see
   * them whole. */
  TABLE {
    @Override void print(Result result, PrintWriter out, Style style) {
      final List<String> names = result.columnNames();
      final int n = names.size();
      final int[] widths = new int[n];
      // A column is right-aligned if it holds numbers and nothing else.
      final boolean[] numeric = new boolean[n];
      final boolean[] text = new boolean[n];
      for (int i = 0; i < n; i++) {
        widths[i] = names.get(i).length();
      }
      final List<String[]> cells = new ArrayList<>();
      for (Object[] row : result.rows()) {
        final String[] line = new String[n];
        for (int i = 0; i < n; i++) {
          String s = row[i] == null ? "NULL" : oneLine(Formatter.text(row[i]));
          if (s.length() > MAX_WIDTH) {
            s = s.substring(0, MAX_WIDTH - 1) + "…";
          }
          line[i] = s;
          widths[i] = Math.max(widths[i], s.length());
          numeric[i] |= row[i] instanceof Number;
          text[i] |= row[i] != null && !(row[i] instanceof Number);
        }
        cells.add(line);
      }
      rule(out, style, widths, '┌', '┬', '┐');
      final StringBuilder header = new StringBuilder();
      for (int i = 0; i < n; i++) {
        header.append(style.dim("│")).append(' ')
            .append(style.bold(pad(names.get(i), widths[i], false)))
            .append(' ');
      }
      out.println(header.append(style.dim("│")));
      rule(out, style, widths, '├', '┼', '┤');
      for (int r = 0; r < cells.size(); r++) {
        final StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) {
          final String padded =
              pad(cells.get(r)[i], widths[i], numeric[i] && !text[i]);
          b.append(style.dim("│")).append(' ')
              .append(result.rows().get(r)[i] == null ? style.dim(padded)
                  : padded)
              .append(' ');
        }
        out.println(b.append(style.dim("│")));
      }
      rule(out, style, widths, '└', '┴', '┘');
    }
  },

  /** One column per line; nothing is truncated. */
  VERTICAL {
    @Override void print(Result result, PrintWriter out, Style style) {
      final List<String> names = result.columnNames();
      int width = 0;
      for (String name : names) {
        width = Math.max(width, name.length());
      }
      int r = 0;
      for (Object[] row : result.rows()) {
        out.println(style.dim("── row " + ++r + " ──"));
        for (int i = 0; i < names.size(); i++) {
          final String value = row[i] == null ? style.dim("NULL")
              : text(row[i]).replace("\n", "\n" + " ".repeat(width + 3));
          out.println(style.bold(pad(names.get(i), width, true)) + " │ "
              + value);
        }
      }
    }
  },

  /** RFC 4180. */
  CSV {
    @Override void print(Result result, PrintWriter out, Style style) {
      out.println(String.join(",",
          result.columnNames().stream().map(Formatter::csv).toList()));
      for (Object[] row : result.rows()) {
        final List<String> fields = new ArrayList<>();
        for (Object value : row) {
          fields.add(value == null ? "" : csv(text(value)));
        }
        out.println(String.join(",", fields));
      }
    }
  },

  /** An array of objects. */
  JSON {
    @Override void print(Result result, PrintWriter out, Style style) {
      final List<String> names = result.columnNames();
      out.println("[");
      for (int r = 0; r < result.rows().size(); r++) {
        final StringBuilder b = new StringBuilder("  {");
        for (int i = 0; i < names.size(); i++) {
          b.append(i > 0 ? ", " : "");
          json(b, names.get(i));
          b.append(": ");
          json(b, result.rows().get(r)[i]);
        }
        out.println(b.append(r < result.rows().size() - 1 ? "}," : "}"));
      }
      out.println("]");
    }
  };

  private static final int MAX_WIDTH = 60;

  abstract void print(Result result, PrintWriter out, Style style);

  static Formatter of(String name) {
    return valueOf(name.toUpperCase(Locale.ROOT));
  }

  /** Converts a value to text. */
  static String text(Object value) {
    if (value instanceof Object[] array) {
      return Arrays.deepToString(array);
    }
    return String.valueOf(value);
  }

  private static String oneLine(String s) {
    return s.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
  }

  private static String pad(String s, int width, boolean right) {
    final String padding = " ".repeat(Math.max(0, width - s.length()));
    return right ? padding + s : s + padding;
  }

  private static void rule(PrintWriter out, Style style, int[] widths,
      char left, char middle, char right) {
    final StringBuilder b = new StringBuilder().append(left);
    for (int i = 0; i < widths.length; i++) {
      b.append("─".repeat(widths[i] + 2))
          .append(i < widths.length - 1 ? middle : right);
    }
    out.println(style.dim(b.toString()));
  }

  private static String csv(String s) {
    if (s.contains(",") || s.contains("\"") || s.contains("\n")
        || s.contains("\r")) {
      return '"' + s.replace("\"", "\"\"") + '"';
    }
    return s;
  }

  private static void json(StringBuilder b, Object value) {
    if (value == null) {
      b.append("null");
    } else if (value instanceof Number || value instanceof Boolean) {
      b.append(value);
    } else if (value instanceof Map<?, ?> map) {
      b.append('{');
      boolean first = true;
      for (Map.Entry<?, ?> e : map.entrySet()) {
        b.append(first ? "" : ", ");
        first = false;
        json(b, String.valueOf(e.getKey()));
        b.append(": ");
        json(b, e.getValue());
      }
      b.append('}');
    } else if (value instanceof Iterable<?> list) {
      b.append('[');
      boolean first = true;
      for (Object o : list) {
        b.append(first ? "" : ", ");
        first = false;
        json(b, o);
      }
      b.append(']');
    } else if (value instanceof Object[] array) {
      json(b, Arrays.asList(array));
    } else {
      b.append('"');
      for (char c : value.toString().toCharArray()) {
        switch (c) {
        case '"':
          b.append("\\\"");
          break;
        case '\\':
          b.append("\\\\");
          break;
        case '\n':
          b.append("\\n");
          break;
        case '\r':
          b.append("\\r");
          break;
        case '\t':
          b.append("\\t");
          break;
        default:
          if (c < 0x20) {
            b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
          } else {
            b.append(c);
          }
        }
      }
      b.append('"');
    }
  }
}
