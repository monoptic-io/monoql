/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.repl;

import org.jline.reader.Highlighter;
import org.jline.reader.LineReader;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import java.util.regex.Pattern;

/** Colors SQL as it is typed: keywords, strings, numbers, quoted
 * identifiers, comments, and {@code !commands}. */
final class SqlHighlighter implements Highlighter {
  private static final AttributedStyle KEYWORD =
      AttributedStyle.BOLD.foreground(AttributedStyle.BLUE);
  private static final AttributedStyle SOFT_KEYWORD =
      AttributedStyle.DEFAULT.foreground(AttributedStyle.BLUE);
  private static final AttributedStyle STRING =
      AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN);
  private static final AttributedStyle NUMBER =
      AttributedStyle.DEFAULT.foreground(AttributedStyle.MAGENTA);
  private static final AttributedStyle QUOTED =
      AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW);
  private static final AttributedStyle COMMENT =
      AttributedStyle.DEFAULT.foreground(AttributedStyle.BRIGHT + AttributedStyle.BLACK);
  private static final AttributedStyle COMMAND =
      AttributedStyle.BOLD.foreground(AttributedStyle.CYAN);
  private static final AttributedStyle UNTERMINATED =
      AttributedStyle.DEFAULT.foreground(AttributedStyle.RED);

  @Override public AttributedString highlight(LineReader reader,
      String buffer) {
    return highlight(buffer);
  }

  static AttributedString highlight(String buffer) {
    final AttributedStringBuilder b = new AttributedStringBuilder();
    String sql = buffer;
    int offset = 0;
    if (buffer.stripLeading().startsWith("!")) {
      // Color the command; whatever follows may well be SQL.
      int end = buffer.indexOf('!');
      while (end < buffer.length()
          && !Character.isWhitespace(buffer.charAt(end))) {
        end++;
      }
      b.append(buffer.substring(0, end), COMMAND);
      sql = buffer.substring(end);
      offset = end;
    }
    for (SqlLexer.Token token : SqlLexer.lex(sql)) {
      final String text = token.text(sql);
      b.append(text, style(token, text));
    }
    assert b.length() == buffer.length() : offset;
    return b.toAttributedString();
  }

  private static AttributedStyle style(SqlLexer.Token token, String text) {
    if (!token.terminated()) {
      return UNTERMINATED;
    }
    switch (token.kind()) {
    case WORD:
      switch (Keywords.weight(text)) {
      case STRONG:
        return KEYWORD;
      case SOFT:
        return SOFT_KEYWORD;
      default:
        return AttributedStyle.DEFAULT;
      }
    case STRING:
      return STRING;
    case NUMBER:
      return NUMBER;
    case QUOTED:
      return QUOTED;
    case COMMENT:
      return COMMENT;
    default:
      return AttributedStyle.DEFAULT;
    }
  }

  @Override public void setErrorPattern(Pattern errorPattern) {
  }

  @Override public void setErrorIndex(int errorIndex) {
  }
}
