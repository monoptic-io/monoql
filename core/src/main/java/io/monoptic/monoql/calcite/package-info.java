/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * The seams monoql has cut into Calcite's core.
 *
 * <p>Upstream Calcite routes a good deal of its internals through its own
 * JDBC driver ({@code jdbc:calcite:}). monoql does not ship that driver. The
 * classes here are what core uses in its place: a way to plan without a
 * connection ({@link io.monoptic.monoql.calcite.StandalonePrepare}), and an
 * optional hook back to the driver for the few legacy features that cannot
 * live without it ({@link io.monoptic.monoql.calcite.LegacyJdbc}).
 */
package io.monoptic.monoql.calcite;
