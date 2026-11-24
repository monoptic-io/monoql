/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

// A catalog whose metadata is kept in a SQL database, for many tenants, with
// the rows of its tables in a catalog of another type. An ordinary SPI
// implementation, in its own jar.

dependencies {
    api(project(":monoql-catalog"))

    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("org.apache.commons:commons-dbcp2")

    testImplementation(project(":monoql-engine"))
    testImplementation(project(":monoql-jdbc"))
    // The metastore in tests: like PostgreSQL in what matters here, which
    // is that a writer waits for a row another writer has changed.
    testRuntimeOnly("com.h2database:h2:2.5.252")
    // The data catalog in tests.
    testImplementation("org.hsqldb:hsqldb::jdk8")
    testRuntimeOnly("org.postgresql:postgresql:42.7.15")
    testRuntimeOnly("org.apache.logging.log4j:log4j-slf4j-impl")
}

// To test against a real metastore:
//   ./gradlew :monoql-metastore:test \
//     -Dmonoql.test.metastore.url=jdbc:postgresql://localhost/postgres \
//     -Dmonoql.test.metastore.user=... -Dmonoql.test.metastore.password=...
tasks.test {
    for (name in System.getProperties().stringPropertyNames()) {
        if (name.startsWith("monoql.test.")) {
            systemProperty(name, System.getProperty(name))
        }
    }
}
