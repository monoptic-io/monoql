/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.metastore;

import io.monoptic.monoql.catalog.Catalog;
import io.monoptic.monoql.catalog.CatalogException;
import io.monoptic.monoql.catalog.CatalogProvider;

import org.apache.commons.dbcp2.BasicDataSource;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;

import javax.sql.DataSource;

/** Provides {@code type = 'metastore'} catalogs.
 *
 * <p>Options:
 *
 * <ul>
 * <li>{@code url} (required): the JDBC URL of the metastore;
 * <li>{@code driver}: the driver class, if the URL alone does not find it;
 * <li>{@code user}, {@code password};
 * <li>{@code tenant}: whose metadata this catalog is; by default,
 *     {@code default};
 * <li>{@code create}: whether to create the metastore's tables if they are
 *     not there; by default, {@code true};
 * <li>{@code data.type}, and {@code data.} followed by any option of that
 *     type: the catalog that holds the rows of this catalog's tables. For
 *     example, {@code 'data.type' = 'jdbc', 'data.url' = '...'}. Without
 *     these, tables are metadata only.
 * </ul> */
public class MetastoreCatalogProvider implements CatalogProvider {
  private static final Set<String> OPTIONS =
      Set.of("url", "driver", "user", "password", "tenant", "create");
  private static final String DATA = "data.";

  @Override public String type() {
    return "metastore";
  }

  @Override public Catalog create(String name, Map<String, String> options) {
    final Map<String, String> dataOptions = new LinkedHashMap<>();
    final Set<String> unknown = new TreeSet<>();
    for (Map.Entry<String, String> e : options.entrySet()) {
      if (e.getKey().startsWith(DATA)) {
        dataOptions.put(e.getKey().substring(DATA.length()), e.getValue());
      } else if (!OPTIONS.contains(e.getKey())) {
        unknown.add(e.getKey());
      }
    }
    if (!unknown.isEmpty()) {
      throw new CatalogException("Unknown options " + unknown
          + " for a metastore catalog; expected " + new TreeSet<>(OPTIONS)
          + ", and data.type with the options of that type");
    }
    final String url = options.get("url");
    if (url == null) {
      throw new CatalogException("A metastore catalog requires a url");
    }
    final String create = options.getOrDefault("create", "true");
    if (!create.equals("true") && !create.equals("false")) {
      throw new CatalogException("The option create is true or false, not "
          + create);
    }
    final Catalog data = data(name, dataOptions);
    final List<String> where =
        Arrays.asList(url, options.get("driver"), options.get("user"),
            options.get("password"));
    final Runnable release = () -> release(where);
    try {
      return new MetastoreCatalog(acquire(where), release,
          options.getOrDefault("tenant", "default"), name,
          create.equals("true"), data);
    } catch (RuntimeException e) {
      release.run();
      if (data != null) {
        data.close();
      }
      throw e;
    }
  }

  /** Connections to a metastore, and how many catalogs are using them. */
  private static final class Pool {
    final BasicDataSource dataSource = new BasicDataSource();
    int users;
  }

  /** One pool of connections for each metastore, whatever the number of
   * catalogs in it: a server with a thousand tenants has a thousand
   * catalogs, and must not have a thousand pools. Keyed by URL, driver,
   * user and password. */
  private static final Map<List<String>, Pool> POOLS = new HashMap<>();

  private static DataSource acquire(List<String> where) {
    synchronized (POOLS) {
      final Pool pool = POOLS.computeIfAbsent(where, w -> {
        final Pool p = new Pool();
        p.dataSource.setUrl(w.get(0));
        if (w.get(1) != null) {
          p.dataSource.setDriverClassName(w.get(1));
        }
        p.dataSource.setUsername(w.get(2));
        p.dataSource.setPassword(w.get(3));
        return p;
      });
      pool.users++;
      return pool.dataSource;
    }
  }

  private static void release(List<String> where) {
    synchronized (POOLS) {
      final Pool pool = POOLS.get(where);
      if (pool != null && --pool.users == 0) {
        POOLS.remove(where);
        try {
          pool.dataSource.close();
        } catch (SQLException e) {
          // Its connections are closed as far as they can be.
        }
      }
    }
  }

  private static Catalog data(String name, Map<String, String> options) {
    if (options.isEmpty()) {
      return null;
    }
    final String type = options.remove("type");
    if (type == null) {
      throw new CatalogException("A metastore catalog with data options"
          + " needs 'data.type', the type of catalog that holds its rows");
    }
    final Set<String> types = new TreeSet<>();
    for (CatalogProvider provider : ServiceLoader.load(CatalogProvider.class)) {
      if (provider.type().equalsIgnoreCase(type)) {
        return provider.create(name, options);
      }
      types.add(provider.type());
    }
    throw new CatalogException("Unknown catalog type '" + type
        + "' for data.type; available types are " + types);
  }
}
