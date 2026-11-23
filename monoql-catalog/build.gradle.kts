/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

// The catalog API and SPI: table-shaped metadata relations, plus an in-memory
// reference implementation. Depends on calcite-core only for the data-plane
// hook (org.apache.calcite.schema.Table).

dependencies {
    api(project(":core"))

    testImplementation("org.junit.jupiter:junit-jupiter")
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
