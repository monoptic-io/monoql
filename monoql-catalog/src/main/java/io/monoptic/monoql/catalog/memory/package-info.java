/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * An in-memory catalog that supports every metadata table, and stores table
 * rows on the heap. It is the reference implementation of the SPI, the default
 * catalog of a new session, and a convenient fixture for tests.
 */
package io.monoptic.monoql.catalog.memory;
