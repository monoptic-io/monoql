/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

// The monoql engine: the extended parser, the metadata schema, the lowering
// of statements to DML, and a Frameworks-based session that executes plans
// directly, without any JDBC driver.

plugins {
    calcite.fmpp
    calcite.javacc
}

dependencies {
    api(project(":core"))
    api(project(":linq4j"))
    api(project(":monoql-catalog"))

    implementation("com.google.guava:guava")
    implementation("org.slf4j:slf4j-api")

    testImplementation("org.hsqldb:hsqldb::jdk8")
    testRuntimeOnly("org.apache.logging.log4j:log4j-slf4j-impl")
}

tasks.withType<JavaCompile>().configureEach {
    // The JavaCC-generated sources trip this lint.
    options.compilerArgs.add("-Xlint:-dep-ann")
}

val fmppMain by tasks.registering(org.apache.calcite.buildtools.fmpp.FmppTask::class) {
    inputs.dir("src/main/codegen").withPathSensitivity(PathSensitivity.RELATIVE)
    config.set(file("src/main/codegen/config.fmpp"))
    templates.set(file("$rootDir/core/src/main/codegen/templates"))
}

val javaCCMain by tasks.registering(org.apache.calcite.buildtools.javacc.JavaCCTask::class) {
    dependsOn(fmppMain)
    val parserFile = fmppMain.map {
        it.output.asFileTree.matching { include("**/Parser.jj") }
    }
    inputFile.from(parserFile)
    packageName.set("io.monoptic.monoql.sql.parser")
}

sourceSets {
    main {
        java.srcDir(javaCCMain.flatMap { it.output })
    }
}
