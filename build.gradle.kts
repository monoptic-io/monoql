/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

// The build of monoql.
//
// Modules named monoql-* are Monoptic's, and are compiled for Java 21. The
// rest are forked from Apache Calcite (see UPSTREAM.md) and keep upstream's
// Java 8 language level and its test settings.

plugins {
    base
    kotlin("jvm") apply false
}

val String.v: String get() = rootProject.extra["$this.version"] as String

allprojects {
    // Never publish forked artifacts under upstream's coordinates.
    group = "io.monoptic.monoql"
    version = "monoql".v

    repositories {
        mavenCentral()
    }
}

println("Building monoql $version (on Apache Calcite ${"calcite".v})")

subprojects {
    if (name == "bom") {
        return@subprojects
    }
    val monoql = name.startsWith("monoql")

    apply(plugin = "java-library")
    apply(plugin = "maven-publish")

    configurations {
        "implementation" {
            exclude(group = "org.jetbrains", module = "annotations")
            exclude(group = "org.bouncycastle", module = "bcprov-jdk15on")
        }
    }

    dependencies {
        "annotationProcessor"(platform(project(":bom")))
        "implementation"(platform(project(":bom")))
        "testAnnotationProcessor"(platform(project(":bom")))
        "testImplementation"(platform("org.junit:junit-bom"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testImplementation"("org.hamcrest:hamcrest")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    configure<JavaPluginExtension> {
        val level = if (monoql) JavaVersion.VERSION_21 else JavaVersion.VERSION_1_8
        sourceCompatibility = level
        targetCompatibility = level
        withSourcesJar()
        consistentResolution {
            useCompileClasspathVersions()
        }
    }

    configure<BasePluginExtension> {
        // Forked modules keep their upstream artifact names.
        archivesName.set(if (monoql) name else "calcite-$name")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        if (monoql) {
            options.release.set(21)
        }
        options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:-options", "-Werror"))
    }

    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

    tasks.withType<Jar>().configureEach {
        manifest {
            attributes["Implementation-Title"] = "monoql"
            attributes["Implementation-Version"] = project.version
            attributes["Implementation-Vendor"] = "Monoptic LLC"
            attributes["Bundle-License"] = "Apache-2.0"
        }
        metaInf {
            from(rootDir) {
                include("LICENSE", "NOTICE")
            }
        }
    }

    // The test settings are upstream's. Its tests assume them: a Turkish
    // locale to catch case-conversion bugs, UTC, and concurrent execution.
    tasks.withType<Test>().configureEach {
        useJUnitPlatform {
            excludeTags("slow")
        }
        exclude("**/*Suite*")
        jvmArgs("-Xmx1536m")
        fun passProperty(name: String, default: String? = null) {
            (System.getProperty(name) ?: default)?.let { systemProperty(name, it) }
        }
        passProperty("java.awt.headless")
        passProperty("junit.jupiter.execution.parallel.enabled", "true")
        passProperty("junit.jupiter.execution.parallel.mode.default", "concurrent")
        passProperty("junit.jupiter.execution.timeout.default", "5 m")
        passProperty("user.language", "TR")
        passProperty("user.country", "tr")
        passProperty("user.timezone", "UTC")
        passProperty("calcite.avatica.version", "calcite.avatica".v)
        passProperty("gradle.rootDir", rootDir.toString())
        for (e in System.getProperties().stringPropertyNames()) {
            if (e.startsWith("calcite.") || e.startsWith("avatica.")) {
                passProperty(e)
            }
        }
    }

    configure<PublishingExtension> {
        publications {
            create<MavenPublication>("maven") {
                artifactId = the<BasePluginExtension>().archivesName.get()
                from(components["java"])
                // Write the versions that were resolved, and tested with,
                // into the POM, so that it stands alone.
                versionMapping {
                    usage(Usage.JAVA_RUNTIME) {
                        fromResolutionResult()
                    }
                    usage(Usage.JAVA_API) {
                        fromResolutionOf("runtimeClasspath")
                    }
                }
                pom {
                    name.set(artifactId)
                    description.set(
                        if (monoql) {
                            "monoql: a SQL engine on a fork of Apache Calcite"
                        } else {
                            "Part of monoql; forked from Apache Calcite ${"calcite".v}"
                        }
                    )
                    url.set("https://monoptic.io")
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    organization {
                        name.set("Monoptic LLC")
                        url.set("https://monoptic.io")
                    }
                    withXml {
                        val sb = asString()
                        val s = sb.toString().replace(
                            Regex("<dependencyManagement>.*?</dependencyManagement>", RegexOption.DOT_MATCHES_ALL),
                            ""
                        )
                        sb.setLength(0)
                        sb.append(s)
                    }
                }
            }
        }
    }
}
