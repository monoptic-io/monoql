/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

// The monoql shell: an interactive REPL over a Session. It talks to the
// engine directly; there is no JDBC anywhere in it.

plugins {
    application
}

dependencies {
    implementation(project(":monoql-engine"))
    implementation("org.jline:jline")

    // So that CREATE CATALOG ... WITH (type = 'jdbc') works out of the box.
    // The database's own driver comes from --jar.
    runtimeOnly(project(":monoql-jdbc"))
    runtimeOnly(project(":monoql-metastore"))
    runtimeOnly("org.apache.logging.log4j:log4j-slf4j-impl")

}

application {
    mainClass.set("io.monoptic.monoql.repl.Repl")
    applicationName = "monoql"
    // JLine reaches the terminal through native calls.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}
