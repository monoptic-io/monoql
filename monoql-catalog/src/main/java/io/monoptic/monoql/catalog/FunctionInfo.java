/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.catalog;

import java.util.Map;

/** A row of {@code metadata.FUNCTIONS}.
 *
 * @param schemaName Schema
 * @param functionName Name
 * @param functionLanguage Implementation language, e.g. {@code JAVA}, {@code SQL},
 *     {@code PYTHON}. The engine can call {@code JAVA} functions itself; other
 *     languages are for the backend
 * @param definition For {@code JAVA}, a class name, optionally followed by
 *     {@code #method}; otherwise whatever the language calls for
 * @param comment Comment, or null
 * @param options Options */
public record FunctionInfo(String schemaName, String functionName,
    String functionLanguage, String definition, String comment,
    Map<String, String> options) implements MetaRow {
  public FunctionInfo {
    Rows.required(schemaName, "schemaName");
    Rows.required(functionName, "functionName");
    Rows.required(functionLanguage, "functionLanguage");
    Rows.required(definition, "definition");
    options = Rows.map(options);
  }

  @Override public Key key() {
    return Key.of(schemaName, functionName);
  }
}
