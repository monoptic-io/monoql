/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
plugins {
    `java-platform`
}

val String.v: String get() = rootProject.extra["$this.version"] as String

// Note: Gradle allows to declare dependency on "bom" as "api",
// and it makes the constraints to be transitively visible
// However Maven can't express that, so the approach is to use Gradle resolution
// and generate pom files with resolved versions
// See https://github.com/gradle/gradle/issues/9866

fun DependencyConstraintHandlerScope.apiv(
    notation: String,
    versionProp: String = notation.substringAfterLast(':')
) =
    "api"(notation + ":" + versionProp.v)

fun DependencyConstraintHandlerScope.runtimev(
    notation: String,
    versionProp: String = notation.substringAfterLast(':')
) =
    "runtime"(notation + ":" + versionProp.v)

javaPlatform {
    allowDependencies()
}

dependencies {
    api(platform("com.fasterxml.jackson:jackson-bom:${"jackson".v}"))

    // Parenthesis are needed here: https://github.com/gradle/gradle/issues/9248
    (constraints) {
        // api means "the dependency is for both compilation and runtime"
        // runtime means "the dependency is only for runtime, not for compilation"
        // In other words, marking dependency as "runtime" would avoid accidental
        // dependency on it during compilation
        apiv("com.google.errorprone:error_prone_annotations", "errorprone")
        apiv("com.google.guava:guava")
        apiv("com.google.protobuf:protobuf-java", "protobuf")
        apiv("com.google.uzaygezen:uzaygezen-core", "uzaygezen")
        apiv("com.jayway.jsonpath:json-path")
        apiv("com.yahoo.datasketches:sketches-core")
        apiv("commons-codec:commons-codec")
        apiv("commons-io:commons-io")
        apiv("org.checkerframework:checker-qual", "checkerframework")
        apiv("org.locationtech.jts:jts-core")
        apiv("org.locationtech.jts.io:jts-io-common")
        apiv("org.locationtech.proj4j:proj4j")
        apiv("org.locationtech.proj4j:proj4j-epsg", "proj4j")
        apiv("javax.servlet:javax.servlet-api", "servlet")
        apiv("net.hydromatic:aggdesigner-algorithm")
        apiv("net.hydromatic:foodmart-data-hsqldb")
        apiv("net.hydromatic:foodmart-queries")
        apiv("net.hydromatic:quidem")
        apiv("net.hydromatic:scott-data-hsqldb")
        apiv("net.hydromatic:steelwheels-data-hsqldb")
        apiv("net.minidev:json-smart")
        apiv("org.apache.calcite.avatica:avatica-core", "calcite.avatica")
        apiv("org.apache.calcite.avatica:avatica-server", "calcite.avatica")
        apiv("org.apache.commons:commons-dbcp2")
        apiv("org.apache.commons:commons-math3")
        apiv("org.apache.commons:commons-pool2")
        apiv("org.apache.commons:commons-text")
        apiv("org.apache.logging.log4j:log4j-api", "log4j2")
        apiv("org.apache.logging.log4j:log4j-core", "log4j2")
        apiv("org.apache.logging.log4j:log4j-slf4j-impl", "log4j2")
        apiv("org.apiguardian:apiguardian-api")
        apiv("net.bytebuddy:byte-buddy")
        apiv("org.codehaus.janino:commons-compiler", "janino")
        apiv("org.codehaus.janino:janino")
        apiv("org.eclipse.jetty:jetty-http", "jetty")
        apiv("org.eclipse.jetty:jetty-security", "jetty")
        apiv("org.eclipse.jetty:jetty-server", "jetty")
        apiv("org.eclipse.jetty:jetty-util", "jetty")
        apiv("org.immutables:value-annotations", "immutables")
        apiv("org.immutables:value", "immutables")
        apiv("org.hamcrest:hamcrest")
        apiv("org.hsqldb:hsqldb")
        apiv("org.incava:java-diff")
        apiv("org.jooq:joou-java-6", "joou")
        apiv("org.junit:junit-bom", "junit5")
        apiv("org.ow2.asm:asm")
        apiv("org.slf4j:slf4j-api", "slf4j")
        apiv("org.jline:jline")
        apiv("com.google.code.findbugs:jsr305")
    }
}
