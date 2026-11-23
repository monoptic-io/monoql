/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

pluginManagement {
    plugins {
        kotlin("jvm") version extra["kotlin.version"].toString()
    }
}

rootProject.name = "monoql"

include(
    // Calcite, forked
    "bom",
    "linq4j",
    "core",
    "legacy-jdbc",
    "testkit",
    // monoql
    "monoql-catalog",
    "monoql-engine",
    "monoql-jdbc",
    "monoql-repl"
)
