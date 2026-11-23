/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

// Calcite's core: parser, validator, planner and runtime. Forked; see
// UPSTREAM.md. The build is monoql's.

plugins {
    kotlin("jvm")
    calcite.fmpp
    calcite.javacc
}

dependencies {
    api(project(":linq4j"))

    api("org.locationtech.jts:jts-core")
    api("org.locationtech.jts.io:jts-io-common")
    api("org.locationtech.proj4j:proj4j")
    api("com.fasterxml.jackson.core:jackson-annotations")
    api("com.google.errorprone:error_prone_annotations")
    api("com.google.guava:guava")
    api("org.apache.calcite.avatica:avatica-core")
    api("org.apiguardian:apiguardian-api")
    api("org.checkerframework:checker-qual")
    api("org.slf4j:slf4j-api")

    implementation("com.fasterxml.jackson.core:jackson-core")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
    implementation("com.google.uzaygezen:uzaygezen-core") {
        exclude("log4j", "log4j").because("conflicts with log4j-slf4j-impl which uses log4j2 and" +
                " also leaks transitively to projects depending on calcite-core")
    }
    implementation("com.jayway.jsonpath:json-path")
    implementation("com.yahoo.datasketches:sketches-core")
    implementation("commons-codec:commons-codec")
    implementation("net.hydromatic:aggdesigner-algorithm")
    implementation("org.apache.commons:commons-dbcp2")
    implementation("org.apache.commons:commons-math3")
    implementation("org.apache.commons:commons-text")
    implementation("org.jooq:joou-java-6")
    implementation("commons-io:commons-io")
    implementation("org.codehaus.janino:commons-compiler")
    implementation("org.codehaus.janino:janino")
    annotationProcessor("org.immutables:value")
    compileOnly("org.immutables:value-annotations")
    compileOnly("com.google.code.findbugs:jsr305")
    testAnnotationProcessor("org.immutables:value")
    testCompileOnly("org.immutables:value-annotations")
    testCompileOnly("com.google.code.findbugs:jsr305")

    testImplementation(project(":testkit"))
    testImplementation("net.bytebuddy:byte-buddy")
    testImplementation("net.hydromatic:foodmart-queries")
    testImplementation("net.hydromatic:quidem")
    testImplementation("org.apache.calcite.avatica:avatica-server")
    testImplementation("org.apache.commons:commons-pool2")
    testImplementation("org.hsqldb:hsqldb::jdk8")
    testImplementation(kotlin("stdlib-jdk8"))
    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit5"))

    // proj4j-epsg must not be converted to 'implementation' due to its license
    testRuntimeOnly("org.locationtech.proj4j:proj4j-epsg")

    testRuntimeOnly("org.apache.logging.log4j:log4j-slf4j-impl")
}

// Downstream parsers (monoql-engine's among them) extend these templates.
tasks.jar {
    from("src/main/codegen") {
        into("codegen")
    }
}

val fmppMain by tasks.registering(org.apache.calcite.buildtools.fmpp.FmppTask::class) {
    config.set(file("src/main/codegen/config.fmpp"))
    templates.set(file("src/main/codegen/templates"))
}

val javaCCMain by tasks.registering(org.apache.calcite.buildtools.javacc.JavaCCTask::class) {
    dependsOn(fmppMain)
    inputFile.from(fmppMain.map { it.output.asFileTree.matching { include("**/Parser.jj") } })
    packageName.set("org.apache.calcite.sql.parser.impl")
}

// A parser with extensions, which the tests of the extension mechanism use.
val fmppTest by tasks.registering(org.apache.calcite.buildtools.fmpp.FmppTask::class) {
    config.set(file("src/test/codegen/config.fmpp"))
    templates.set(file("src/main/codegen/templates"))
}

val javaCCTest by tasks.registering(org.apache.calcite.buildtools.javacc.JavaCCTask::class) {
    dependsOn(fmppTest)
    inputFile.from(fmppTest.map { it.output.asFileTree.matching { include("**/Parser.jj") } })
    packageName.set("org.apache.calcite.sql.parser.parserextensiontesting")
}

sourceSets {
    main {
        java.srcDir(javaCCMain.flatMap { it.output })
    }
    test {
        java.srcDir(javaCCTest.flatMap { it.output })
    }
}

tasks.withType<JavaCompile>().configureEach {
    // The JavaCC-generated sources trip this lint.
    options.compilerArgs.add("-Xlint:-dep-ann")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}
