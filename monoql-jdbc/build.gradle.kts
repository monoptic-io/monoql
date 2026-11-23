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
    testImplementation("org.hsqldb:hsqldb::jdk8")
    testRuntimeOnly("org.apache.logging.log4j:log4j-slf4j-impl")
}
