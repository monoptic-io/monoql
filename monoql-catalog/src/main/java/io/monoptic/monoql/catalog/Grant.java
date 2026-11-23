/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql.catalog;

/** A row of {@code metadata.GRANTS}: one privilege, on one object, held by
 * one principal.
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
 *     ({@code WITH GRANT OPTION}) */
public record Grant(String privilege, String objectType, String objectSchema,
    String objectName, String granteeType, String grantee, boolean grantable)
    implements MetaRow {
  /** Grantee type used when a statement names a principal without saying
   * what kind it is. */
  public static final String PRINCIPAL = "PRINCIPAL";

  public Grant {
    Rows.required(privilege, "privilege");
    Rows.required(objectType, "objectType");
    Rows.required(granteeType, "granteeType");
    Rows.required(grantee, "grantee");
  }

  /** Grantability is an attribute of a grant, not part of its identity. */
  @Override public Key key() {
    return Key.of(privilege, objectType, objectSchema, objectName,
        granteeType, grantee);
  }
}
