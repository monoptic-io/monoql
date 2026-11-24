/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.repl;

import io.monoptic.monoql.Monoql;

import org.apache.calcite.sql.parser.SqlAbstractParserImpl;
import org.apache.calcite.sql.parser.SqlParser;

import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/** The vocabulary of the grammar, taken from the parser itself, so that it
 * can never fall out of date. */
final class Keywords {
  private static final SqlAbstractParserImpl.Metadata METADATA =
      SqlParser.create("", Monoql.parserConfig()).getMetadata();

  /** Words that are not reserved, but that read as keywords in the
   * statements monoql adds. */
  private static final Set<String> STATEMENT_WORDS =
      Set.of("CATALOG", "CATALOGS", "SCHEMA", "SCHEMAS", "DATABASE",
          "DATABASES", "TABLES", "VIEW", "VIEWS", "MATERIALIZED", "ROLE",
          "ROLES", "USERS", "GRANTS", "COLUMNS", "PARTITIONS", "FUNCTIONS",
          "TRIGGERS", "SETTINGS", "PAUSE", "RESUME", "FIRE", "REFRESH", "USE",
          "IF", "COMMENT", "SCHEDULE", "CONTINUOUSLY", "PARTITIONED",
          "OPTIONS", "TBLPROPERTIES", "PRIVILEGES", "USAGE", "OPTION", "RENAME",
          "BEFORE", "AFTER", "REPLACE", "KEY", "ADD", "UPSERT");

  /** Words offered by completion: the reserved words, plus the statement
   * words. (The grammar knows hundreds of other non-reserved keywords, such
   * as the names of every SQLSTATE field; offering those would be noise.) */
  static final Set<String> COMPLETABLE = completable();

  private Keywords() {
  }

  private static Set<String> completable() {
    final Set<String> words = new TreeSet<>(STATEMENT_WORDS);
    for (String token : METADATA.getTokens()) {
      if (METADATA.isReservedWord(token) && token.matches("[A-Z_]+")) {
        words.add(token);
      }
    }
    return words;
  }

  /** How a word should be highlighted. */
  enum Weight { NONE, SOFT, STRONG }

  static Weight weight(String word) {
    final String upper = word.toUpperCase(Locale.ROOT);
    if (METADATA.isReservedWord(upper)) {
      return Weight.STRONG;
    }
    return STATEMENT_WORDS.contains(upper) ? Weight.SOFT : Weight.NONE;
  }

  static boolean isReserved(String word) {
    return METADATA.isReservedWord(word.toUpperCase(Locale.ROOT));
  }
}
