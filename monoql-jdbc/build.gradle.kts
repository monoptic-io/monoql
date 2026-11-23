/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

// A catalog over any JDBC database: "CREATE CATALOG c WITH (type = 'jdbc',
// url = '...')". It is an ordinary implementation of the catalog SPI, found
// through ServiceLoader, and it shows what partial support looks like.
//
// This is JDBC as a client of other databases. It has nothing to do with
// serving monoql over JDBC, which monoql does not do.

dependencies {
    api(project(":monoql-catalog"))

    implementation(project(":linq4j"))
    implementation("org.apache.commons:commons-dbcp2")

    testImplementation(project(":monoql-engine"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.hsqldb:hsqldb::jdk8")
    testRuntimeOnly("org.apache.logging.log4j:log4j-slf4j-impl")
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
