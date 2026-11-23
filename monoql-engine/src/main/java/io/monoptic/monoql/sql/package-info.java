/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Parse tree nodes for monoql's DDL, DCL and control statements.
 *
 * <p>None of these nodes is executed directly. Each one is lowered to DML
 * (or a query) against the {@code metadata} schema; see
 * {@code io.monoptic.monoql.engine.Lowering}.
 */
package io.monoptic.monoql.sql;
