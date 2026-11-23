/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

plugins {
    `embedded-kotlin`
    `kotlin-dsl` apply false
}

allprojects {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

subprojects {
    apply(plugin = "org.gradle.kotlin.kotlin-dsl")
}

dependencies {
    subprojects.forEach {
        runtimeOnly(project(it.path))
    }
}
