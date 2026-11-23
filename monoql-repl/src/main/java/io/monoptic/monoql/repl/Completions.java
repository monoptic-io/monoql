/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.repl;

import io.monoptic.monoql.Session;

import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.lookup.LikePattern;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Works out what could come next: commands, keywords, and the names of
 * catalogs, schemas, tables and columns.
 *
 * <p>Names come from walking the session's schema tree, so completion sees
 * exactly what the planner sees, in any catalog. */
final class Completions {
  /** A suggestion.
   *
   * @param value What to insert
   * @param kind What it is, for grouping: {@code command}, {@code keyword},
   *     {@code catalog}, {@code schema}, {@code table} or {@code column} */
  record Candidate(String value, String kind) {
  }

  private final Session session;

  Completions(Session session) {
    this.session = session;
  }

  /** Suggests completions of a word.
   *
   * @param buffer Everything typed so far
   * @param word The word under the cursor, up to the cursor */
  List<Candidate> complete(String buffer, String word) {
    final Map<String, Candidate> candidates = new LinkedHashMap<>();
    try {
      if (buffer.stripLeading().startsWith("!")
          && buffer.stripLeading().equals(word)) {
        for (Shell.Command command : Shell.COMMANDS) {
          add(candidates, word, "!" + command.name(), "command", false);
        }
      } else if (word.contains(".")) {
        final int dot = word.lastIndexOf('.');
        final String prefix = word.substring(0, dot + 1);
        final List<String> path = unquote(word.substring(0, dot).split("\\."));
        final String last = word.substring(dot + 1);
        for (Object o : resolve(path)) {
          if (o instanceof SchemaPlus schema) {
            children(candidates, schema, prefix, last);
          } else if (o instanceof Table table) {
            columns(candidates, table, prefix, last);
          }
        }
      } else {
        for (List<String> path : defaultPaths()) {
          for (Object o : resolveExact(path)) {
            children(candidates, (SchemaPlus) o, "", word);
          }
        }
        // Columns of the tables the statement mentions.
        for (SqlLexer.Token token : SqlLexer.lex(buffer)) {
          if (token.kind() == SqlLexer.Kind.WORD
              || token.kind() == SqlLexer.Kind.QUOTED) {
            final List<String> path =
                unquote(new String[] {token.text(buffer)});
            for (Object o : resolve(path)) {
              if (o instanceof Table table) {
                columns(candidates, table, "", word);
              }
            }
          }
        }
        if (!word.isEmpty()) {
          final boolean lower = Character.isLowerCase(word.charAt(0));
          for (String keyword : Keywords.COMPLETABLE) {
            if (keyword.regionMatches(true, 0, word, 0, word.length())) {
              final String value =
                  lower ? keyword.toLowerCase(Locale.ROOT) : keyword;
              candidates.putIfAbsent(value.toUpperCase(Locale.ROOT),
                  new Candidate(value, "keyword"));
            }
          }
        }
      }
    } catch (RuntimeException e) {
      // Completion is a convenience. A catalog that fails to list its
      // tables should not break typing.
    }
    return new ArrayList<>(candidates.values());
  }

  private List<List<String>> defaultPaths() {
    final List<List<String>> paths = new ArrayList<>();
    final String catalog = session.currentCatalog();
    final String schema = session.currentSchema();
    if (catalog != null && schema != null) {
      paths.add(List.of(catalog, schema));
    }
    if (catalog != null) {
      paths.add(List.of(catalog));
    }
    paths.add(List.of());
    return paths;
  }

  /** Resolves a path against each default path; returns the schemas and
   * tables it could mean. */
  private List<Object> resolve(List<String> path) {
    final List<Object> found = new ArrayList<>();
    for (List<String> base : defaultPaths()) {
      final List<String> full = new ArrayList<>(base);
      full.addAll(path);
      found.addAll(resolveExact(full));
    }
    return found;
  }

  /** Resolves an absolute path to a schema or a table. */
  private List<Object> resolveExact(List<String> path) {
    SchemaPlus schema = session.rootSchema();
    for (int i = 0; i < path.size(); i++) {
      final String name = path.get(i);
      final SchemaPlus child = schema.subSchemas().get(name);
      if (child != null) {
        schema = child;
        continue;
      }
      if (i == path.size() - 1) {
        final Table table = schema.tables().get(name);
        return table == null ? List.of() : List.of(table);
      }
      return List.of();
    }
    return List.of(schema);
  }

  private void children(Map<String, Candidate> candidates, SchemaPlus schema,
      String prefix, String word) {
    final boolean root = schema.getParentSchema() == null;
    for (String name : schema.subSchemas().getNames(LikePattern.any())) {
      add(candidates, word, prefix, name, root ? "catalog" : "schema");
    }
    for (String name : schema.tables().getNames(LikePattern.any())) {
      add(candidates, word, prefix, name, "table");
    }
  }

  private void columns(Map<String, Candidate> candidates, Table table,
      String prefix, String word) {
    for (String name
        : table.getRowType(new JavaTypeFactoryImpl()).getFieldNames()) {
      add(candidates, word, prefix, name, "column");
    }
  }

  private static void add(Map<String, Candidate> candidates, String word,
      String prefix, String name, String kind) {
    // The first thing typed sets the case, even if the cursor is now
    // after a dot.
    add(candidates, word, prefix + render(name, prefix + word), kind,
        !name.regionMatches(true, 0, unquote(word), 0,
            unquote(word).length()));
  }

  private static void add(Map<String, Candidate> candidates, String word,
      String value, String kind, boolean skip) {
    if (!skip && (kind.equals("command")
        ? value.regionMatches(true, 0, word, 0, word.length()) : true)) {
      candidates.putIfAbsent(value.toUpperCase(Locale.ROOT),
          new Candidate(value, kind));
    }
  }

  /** Writes a name so that it parses back to itself. A plain upper-case
   * name is offered in the case being typed, because unquoted identifiers
   * fold to upper case; anything else needs quotes. */
  static String render(String name, String typed) {
    final boolean plain = name.matches("[A-Z_][A-Z0-9_$]*")
        && !Keywords.isReserved(name);
    if (!plain) {
      return '"' + name.replace("\"", "\"\"") + '"';
    }
    final String letters = unquote(typed);
    return !letters.isEmpty() && Character.isLowerCase(letters.charAt(0))
        ? name.toLowerCase(Locale.ROOT) : name;
  }

  private static String unquote(String word) {
    return word.startsWith("\"") ? word.substring(1).replace("\"", "")
        : word;
  }

  /** Applies the parser's rule to each part of a typed path: quoted parts
   * are taken as they are, unquoted parts fold to upper case. */
  private static List<String> unquote(String[] parts) {
    final List<String> names = new ArrayList<>();
    for (String part : parts) {
      names.add(part.startsWith("\"")
          ? part.substring(1, Math.max(1, part.length()
              - (part.length() > 1 && part.endsWith("\"") ? 1 : 0)))
          : part.toUpperCase(Locale.ROOT));
    }
    return names;
  }
}
