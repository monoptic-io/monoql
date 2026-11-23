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
    runtimeOnly("org.apache.logging.log4j:log4j-slf4j-impl")

    testImplementation("org.junit.jupiter:junit-jupiter")
}

application {
    mainClass.set("io.monoptic.monoql.repl.Repl")
    applicationName = "monoql"
    // JLine reaches the terminal through native calls.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).source = "21"
}

// New io.monoptic code carries a Monoptic header rather than the ASF one, and
// does not follow upstream's checkstyle profile.
tasks.matching { it.name.startsWith("checkstyle") || it.name.startsWith("autostyle") }
    .configureEach { enabled = false }

// A shell writes to System.out; that is its job.
tasks.matching { it.name.startsWith("forbiddenApis") }
    .configureEach { enabled = false }
