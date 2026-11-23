/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * The monoql catalog API and SPI.
 *
 * <p>A {@link io.monoptic.monoql.catalog.Catalog} is a bundle of
 * {@link io.monoptic.monoql.catalog.MetaTable metadata tables}: schemas,
 * tables, views, triggers, grants and so on. Each one is a small relation of
 * typed rows that can be scanned, inserted into, updated and deleted from.
 * There is no other way to change a catalog: {@code GRANT} is an insert into
 * the grants table, {@code DROP TABLE} is a delete from the tables table,
 * {@code PAUSE TRIGGER} is an update of the triggers table.
 *
 * <p>Every metadata table is optional. The default implementation of each
 * accessor returns a table whose operations throw
 * {@link io.monoptic.monoql.catalog.UnsupportedException}, so an adapter only
 * implements what its backend understands.
 */
package io.monoptic.monoql.catalog;
