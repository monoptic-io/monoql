/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * monoql: a SQL engine built on a fork of Apache Calcite.
 *
 * <p>Start with {@link io.monoptic.monoql.Monoql#builder()}, which yields a
 * {@link io.monoptic.monoql.Session}. A session parses, plans and executes
 * statements in-process, through Calcite's {@code Frameworks} machinery;
 * there is no JDBC driver involved.
 */
package io.monoptic.monoql;
