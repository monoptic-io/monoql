/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

// Calcite's own JDBC driver ("jdbc:calcite:") and the Avatica-based
// connection, statement and metadata classes behind it.
//
// monoql does not use or ship this. It was moved out of core, verbatim, so
// that core has no driver in it; it is kept because most of upstream's test
// suite drives Calcite through it, and that suite is how we know the planner
// still works after a merge. testkit depends on it; nothing under io.monoptic
// may.

dependencies {
    api(project(":core"))
    api(project(":linq4j"))
    api("org.apache.calcite.avatica:avatica-core")

    implementation("com.google.guava:guava")
    implementation("org.slf4j:slf4j-api")
    implementation("org.checkerframework:checker-qual")
}

// The sources here are upstream's, moved; the package-info files stayed
// behind in core, and one new file has a Monoptic header.
tasks.matching { it.name.startsWith("checkstyle") || it.name.startsWith("autostyle") }
    .configureEach { enabled = false }
