/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.engine;

import io.monoptic.monoql.catalog.AlreadyExistsException;
import io.monoptic.monoql.catalog.Catalog;
import io.monoptic.monoql.catalog.CatalogException;
import io.monoptic.monoql.catalog.CatalogInfo;
import io.monoptic.monoql.catalog.CatalogProvider;
import io.monoptic.monoql.catalog.Key;
import io.monoptic.monoql.catalog.MetaTable;
import io.monoptic.monoql.catalog.NotFoundException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.TreeSet;

/** The catalogs mounted in a session. This is {@code METADATA.CATALOGS}:
 * inserting a row creates a catalog through its {@link CatalogProvider} and
 * mounts it; deleting the row unmounts and closes it. */
final class CatalogRegistry implements MetaTable<CatalogInfo> {
  static final String NAME = "CATALOGS";

  /** Type recorded for a catalog that was handed to the session as an
   * object, rather than created by a provider. */
  static final String EMBEDDED = "embedded";

  private record Mounted(CatalogInfo info, Catalog catalog) {
  }

  private final Map<String, Mounted> mounted = new LinkedHashMap<>();
  private final Map<String, CatalogProvider> providers = new LinkedHashMap<>();
  /** Told when a catalog is mounted, replaced or dropped. */
  private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

  CatalogRegistry(List<CatalogProvider> extraProviders) {
    for (CatalogProvider p : ServiceLoader.load(CatalogProvider.class)) {
      providers.put(p.type().toLowerCase(Locale.ROOT), p);
    }
    for (CatalogProvider p : extraProviders) {
      providers.put(p.type().toLowerCase(Locale.ROOT), p);
    }
  }

  void addListener(Runnable listener) {
    listeners.add(listener);
  }

  void removeListener(Runnable listener) {
    listeners.remove(listener);
  }

  /** Mounts an existing catalog object. */
  synchronized void mount(String name, Catalog catalog, String comment) {
    checkName(name);
    if (mounted.containsKey(name)) {
      throw AlreadyExistsException.of(NAME, Key.of(name));
    }
    mounted.put(name,
        new Mounted(new CatalogInfo(name, EMBEDDED, comment, Map.of()),
            catalog));
  }

  /** Returns the mounted catalogs by name, in mount order. */
  synchronized Map<String, Catalog> catalogs() {
    final Map<String, Catalog> map = new LinkedHashMap<>();
    mounted.forEach((name, m) -> map.put(name, m.catalog));
    return map;
  }

  synchronized Catalog catalog(String name) {
    final Mounted m = mounted.get(name);
    return m == null ? null : m.catalog;
  }

  private static void checkName(String name) {
    if (name.equals(MetadataSchema.NAME)) {
      throw new CatalogException("A catalog cannot be called "
          + MetadataSchema.NAME);
    }
  }

  private Catalog create(CatalogInfo info) {
    final CatalogProvider provider =
        providers.get(info.type().toLowerCase(Locale.ROOT));
    if (provider == null) {
      throw new CatalogException("Unknown catalog type '" + info.type()
          + "'; available types are " + new TreeSet<>(providers.keySet()));
    }
    return provider.create(info.catalogName(), info.options());
  }

  @Override public String name() {
    return NAME;
  }

  @Override public synchronized Iterable<CatalogInfo> scan() {
    final List<CatalogInfo> rows = new ArrayList<>();
    mounted.values().forEach(m -> rows.add(m.info));
    return rows;
  }

  @Override public synchronized Optional<CatalogInfo> get(Key key) {
    final Mounted m = mounted.get(String.valueOf(key.parts().get(0)));
    return Optional.ofNullable(m).map(Mounted::info);
  }

  @Override public synchronized void insert(CatalogInfo row) {
    checkName(row.catalogName());
    if (mounted.containsKey(row.catalogName())) {
      throw AlreadyExistsException.of(NAME, row.key());
    }
    mounted.put(row.catalogName(), new Mounted(row, create(row)));
    listeners.forEach(Runnable::run);
  }

  @Override public synchronized void update(CatalogInfo before,
      CatalogInfo after) {
    final Mounted m = mounted.get(before.catalogName());
    if (m == null) {
      throw NotFoundException.of(NAME, before.key());
    }
    if (!before.catalogName().equals(after.catalogName())) {
      throw new CatalogException("A catalog cannot be renamed");
    }
    if (m.info.type().equals(after.type())
        && m.info.options().equals(after.options())) {
      // Only the comment changed; keep the live catalog.
      mounted.put(after.catalogName(), new Mounted(after, m.catalog));
      return;
    }
    // Create the replacement first, so that a bad definition leaves the
    // existing catalog in place.
    final Catalog replacement = create(after);
    close(m.catalog);
    mounted.put(after.catalogName(), new Mounted(after, replacement));
    listeners.forEach(Runnable::run);
  }

  @Override public synchronized void delete(CatalogInfo row) {
    final Mounted m = mounted.remove(row.catalogName());
    if (m == null) {
      throw NotFoundException.of(NAME, row.key());
    }
    close(m.catalog);
    listeners.forEach(Runnable::run);
  }

  synchronized void closeAll() {
    mounted.values().forEach(m -> close(m.catalog));
    mounted.clear();
  }

  private static void close(Catalog catalog) {
    try {
      catalog.close();
    } catch (RuntimeException e) {
      // Closing is best effort; the catalog is gone from the session
      // either way.
    }
  }
}
