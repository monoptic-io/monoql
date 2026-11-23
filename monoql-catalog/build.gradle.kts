/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

// The catalog API and SPI: table-shaped metadata relations, plus an in-memory
// reference implementation. Depends on calcite-core only for the data-plane
// hook (org.apache.calcite.schema.Table).

dependencies {
    api(project(":core"))

}
