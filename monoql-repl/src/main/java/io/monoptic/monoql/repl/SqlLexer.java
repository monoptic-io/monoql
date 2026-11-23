/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.repl;

import java.util.ArrayList;
import java.util.List;

/** A forgiving lexer for SQL as it is being typed. It never fails: input
 * that is not finished yet (an open string, an open comment) just produces a
 * token marked as unterminated. Used for highlighting, for deciding whether
 * a statement is complete, and for splitting scripts. */
final class SqlLexer {
  private SqlLexer() {
  }

  /** Kinds of token. */
  enum Kind { WORD, QUOTED, STRING, NUMBER, COMMENT, SYMBOL, SPACE }

  /** A token: a kind and a span of the input. */
  record Token(Kind kind, int start, int end, boolean terminated) {
    String text(String input) {
      return input.substring(start, end);
    }
  }

  static List<Token> lex(String s) {
    final List<Token> tokens = new ArrayList<>();
    final int n = s.length();
    int i = 0;
    while (i < n) {
      final char c = s.charAt(i);
      final int start = i;
      if (Character.isWhitespace(c)) {
        while (i < n && Character.isWhitespace(s.charAt(i))) {
          i++;
        }
        tokens.add(new Token(Kind.SPACE, start, i, true));
      } else if (c == '-' && i + 1 < n && s.charAt(i + 1) == '-') {
        while (i < n && s.charAt(i) != '\n') {
          i++;
        }
        tokens.add(new Token(Kind.COMMENT, start, i, true));
      } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
        final int end = s.indexOf("*/", i + 2);
        i = end < 0 ? n : end + 2;
        tokens.add(new Token(Kind.COMMENT, start, i, end >= 0));
      } else if (c == '\'' || c == '"' || c == '`') {
        i++;
        boolean terminated = false;
        while (i < n) {
          if (s.charAt(i) == c) {
            if (i + 1 < n && s.charAt(i + 1) == c) {
              i += 2; // doubled quote
              continue;
            }
            i++;
            terminated = true;
            break;
          }
          i++;
        }
        tokens.add(
            new Token(c == '\'' ? Kind.STRING : Kind.QUOTED, start, i,
                terminated));
      } else if (Character.isDigit(c)) {
        while (i < n
            && (Character.isLetterOrDigit(s.charAt(i))
                || s.charAt(i) == '.')) {
          i++;
        }
        tokens.add(new Token(Kind.NUMBER, start, i, true));
      } else if (Character.isLetter(c) || c == '_') {
        while (i < n
            && (Character.isLetterOrDigit(s.charAt(i))
                || s.charAt(i) == '_' || s.charAt(i) == '$')) {
          i++;
        }
        tokens.add(new Token(Kind.WORD, start, i, true));
      } else {
        i++;
        tokens.add(new Token(Kind.SYMBOL, start, i, true));
      }
    }
    return tokens;
  }

  /** Whether a buffer is ready to run: it is blank, or a {@code !command},
   * or SQL that ends in a semicolon. */
  static boolean isComplete(String buffer) {
    final String trimmed = buffer.strip();
    if (trimmed.isEmpty() || trimmed.startsWith("!")) {
      return true;
    }
    Token last = null;
    for (Token token : lex(buffer)) {
      if (!token.terminated()) {
        return false;
      }
      if (token.kind() != Kind.SPACE && token.kind() != Kind.COMMENT) {
        last = token;
      }
    }
    // Nothing but comments is complete too: there is nothing to wait for.
    return last == null
        || last.kind() == Kind.SYMBOL && buffer.charAt(last.start()) == ';';
  }

  /** Splits a script into inputs for {@link Shell#execute}: each is one
   * {@code !command} line, or one SQL statement including its semicolon. */
  static List<String> split(String script) {
    final List<String> inputs = new ArrayList<>();
    final StringBuilder pending = new StringBuilder();
    for (String line : script.split("\n", -1)) {
      if (pending.isEmpty() && line.strip().startsWith("!")) {
        inputs.add(line.strip());
        continue;
      }
      pending.append(line).append('\n');
      if (isComplete(pending.toString())) {
        if (!pending.toString().isBlank()) {
          inputs.add(pending.toString().strip());
        }
        pending.setLength(0);
      }
    }
    if (!pending.toString().isBlank()) {
      inputs.add(pending.toString().strip());
    }
    return inputs;
  }
}
