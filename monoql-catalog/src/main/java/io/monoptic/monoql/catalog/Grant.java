/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.catalog;

import java.util.Set;

/** A row of {@code metadata.GRANTS}: one privilege, on one object, held by
 * one principal.
 *
 * <p>A grant of a privilege to write rows may have a <em>source</em>: it
 * then allows writing only what a statement reads from there. A grant with
 * no source allows it whatever the statement reads. A source is levelled as
 * an object is: a catalog, a schema of it, or one table or view.
 *
 * @param privilege Privilege, upper case, words separated by one space: e.g.
 *     {@code SELECT}, {@code CREATE TABLE}, {@code ALL PRIVILEGES}. Backends
 *     with named roles in place of privileges (e.g. BigQuery's
 *     {@code roles/bigquery.dataViewer}) see the name verbatim
 * @param objectType Kind of object: {@code CATALOG}, {@code SCHEMA},
 *     {@code TABLE}, {@code VIEW}, {@code FUNCTION}, ...
 * @param objectSchema Schema of the object (for a schema, the schema itself);
 *     null for catalog-level grants
 * @param objectName Name of the object; null for catalog- and schema-level
 *     grants
 * @param granteeType {@code USER}, {@code ROLE} or {@code GROUP}; or
 *     {@code PRINCIPAL} if the statement did not say
 * @param grantee Name of the principal
 * @param grantable Whether the grantee may grant the privilege onward
 *     ({@code WITH GRANT OPTION})
 * @param sourceCatalog Catalog of the source, by the name it is mounted
 *     under; null for a grant with no source
 * @param sourceSchema Schema of the source; null if the source is a whole
 *     catalog
 * @param sourceName Table or view that is the source; null if the source
 *     is a whole schema or catalog */
public record Grant(String privilege, String objectType, String objectSchema,
    String objectName, String granteeType, String grantee, boolean grantable,
    String sourceCatalog, String sourceSchema, String sourceName)
    implements MetaRow {
  /** Grantee type used when a statement names a principal without saying
   * what kind it is. */
  public static final String PRINCIPAL = "PRINCIPAL";

  /** The privileges a source can narrow: those to write rows. */
  public static final Set<String> SOURCED =
      Set.of("INSERT", "UPDATE", "DELETE");

  public Grant {
    Rows.required(privilege, "privilege");
    Rows.required(objectType, "objectType");
    Rows.required(granteeType, "granteeType");
    Rows.required(grantee, "grantee");
    if (sourceCatalog == null
        ? sourceSchema != null || sourceName != null
        : sourceSchema == null && sourceName != null) {
      throw new IllegalArgumentException("The source of a grant is a catalog,"
          + " a schema of a catalog, or a table of a schema");
    }
    if (sourceCatalog != null && !SOURCED.contains(privilege)) {
      throw new UnsupportedException("Only INSERT, UPDATE and DELETE can be"
          + " granted of what is read from a source, not " + privilege);
    }
  }

  /** Creates a grant with no source. */
  public Grant(String privilege, String objectType, String objectSchema,
      String objectName, String granteeType, String grantee,
      boolean grantable) {
    this(privilege, objectType, objectSchema, objectName, granteeType,
        grantee, grantable, null, null, null);
  }

  /** Whether the grant allows writing only what is read from a source. */
  public boolean sourced() {
    return sourceCatalog != null;
  }

  /** Grantability is an attribute of a grant, not part of its identity.
   * Its source is part of it, so that the same privilege can be held of
   * several sources; a grant with no source has the key it would have if
   * grants had none. */
  @Override public Key key() {
    return sourceCatalog == null
        ? Key.of(privilege, objectType, objectSchema, objectName,
            granteeType, grantee)
        : Key.of(privilege, objectType, objectSchema, objectName,
            granteeType, grantee, sourceCatalog, sourceSchema, sourceName);
  }
}
