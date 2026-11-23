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
    id("com.github.vlsi.ide")
}

dependencies {
    api(project(":core"))
    api(project(":linq4j"))
    api(project(":monoql-catalog"))

    implementation("com.google.guava:guava")
    implementation("org.slf4j:slf4j-api")

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
    // The JavaCC-generated sources trip this lint under newer language levels.
    options.compilerArgs.add("-Xlint:-dep-ann")
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).source = "21"
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

// New io.monoptic code carries a Monoptic header rather than the ASF one, and
// does not follow upstream's checkstyle profile.
tasks.matching { it.name.startsWith("checkstyle") || it.name.startsWith("autostyle") }
    .configureEach { enabled = false }

ide {
    fun generatedSource(javacc: TaskProvider<org.apache.calcite.buildtools.javacc.JavaCCTask>, sourceSet: String) =
        generatedJavaSources(javacc.get(), javacc.get().output.get().asFile, sourceSets.named(sourceSet))

    generatedSource(javaCCMain, "main")
}
