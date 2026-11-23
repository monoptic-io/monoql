/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.repl;

/** ANSI styling that can be switched off, for pipes and files. */
final class Style {
  private final boolean enabled;

  Style(boolean enabled) {
    this.enabled = enabled;
  }

  boolean enabled() {
    return enabled;
  }

  private String wrap(String code, String s) {
    return enabled ? "\u001b[" + code + "m" + s + "\u001b[0m" : s;
  }

  String bold(String s) {
    return wrap("1", s);
  }

  String dim(String s) {
    return wrap("2", s);
  }

  String red(String s) {
    return wrap("31", s);
  }

  String green(String s) {
    return wrap("32", s);
  }

  String cyan(String s) {
    return wrap("36", s);
  }
}
