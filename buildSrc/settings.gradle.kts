/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

// The two code generators the parser build needs. These are Calcite's.

include("javacc")
include("fmpp")

for (project in rootProject.children) {
    project.projectDir = file("subprojects/${project.name}")
    project.buildFileName = "${project.name}.gradle.kts"
}
