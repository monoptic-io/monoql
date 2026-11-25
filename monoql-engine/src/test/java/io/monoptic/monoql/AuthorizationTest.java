/*
 * Copyright (c) Monoptic LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.monoptic.monoql;

import io.monoptic.monoql.catalog.AccessDeniedException;
import io.monoptic.monoql.catalog.CatalogException;
import io.monoptic.monoql.catalog.Grant;
import io.monoptic.monoql.catalog.MetaTable;
import io.monoptic.monoql.catalog.memory.MemoryCatalog;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.monoptic.monoql.Sessions.rows;
import static io.monoptic.monoql.Sessions.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests that a session with a user is held to the grants of its
 * catalogs. */
class AuthorizationTest {
  private final MemoryCatalog catalog = new MemoryCatalog();
  /** Acts as nobody: unchecked. Sets things up. */
  private Session root;
  private Session ann;
  private Session bob;

  @BeforeEach void open() {
    root = Monoql.builder().catalog("C", catalog).build();
    run(root, "create schema s", "use c.s",
        "create table t (id int)", "insert into t values (1), (2)",
        "create table u (id int)", "insert into u values (10)");
    ann = root.as(Identity.of("ANN", "ANALYSTS", "STAFF"));
    bob = root.as(Identity.of("BOB"));
  }

  @AfterEach void close() {
    ann.close();
    bob.close();
    root.close();
  }

  private static String denied(Session session, String sql) {
    return assertThrows(AccessDeniedException.class,
        () -> session.execute(sql).close(), sql).getMessage();
  }

  @Test void nothingIsAllowedUntilGranted() {
    assertEquals("User ANN lacks SELECT on C.S.T",
        denied(ann, "select * from t"));
    denied(ann, "insert into t values (3)");
    denied(ann, "update t set id = 0");
    denied(ann, "delete from t");
    denied(ann, "explain plan for select * from t");
    assertThrows(AccessDeniedException.class,
        () -> ann.explain("select * from t"));
    // A session with no user is not checked.
    assertEquals(2, rows(root, "select * from t").size());
  }

  @Test void tablePrivileges() {
    run(root, "grant select on t to user ann");
    assertEquals(List.of("[1]", "[2]"), rows(ann, "select * from t"));
    // SELECT is not INSERT, T is not U, and ANN is not BOB.
    assertEquals("User ANN lacks INSERT on C.S.T",
        denied(ann, "insert into t values (3)"));
    denied(ann, "select * from u");
    denied(ann, "select * from t join u on t.id = u.id");
    denied(ann, "select * from t where id in (select id from u)");
    denied(bob, "select * from t");

    run(root, "grant insert, delete on t to ann");
    run(ann, "insert into t values (3)", "delete from t where id = 1");
    denied(ann, "update t set id = 0");
    // Reading in order to write is still reading.
    denied(ann, "insert into t select id from u");

    run(root, "revoke select on t from user ann");
    denied(ann, "select * from t");

    run(root, "grant all on u to bob");
    run(bob, "insert into u values (11)", "update u set id = 12 where id = 11",
        "delete from u where id = 12");
    assertEquals(List.of("[10]"), rows(bob, "select * from u"));
  }

  @Test void roles() {
    run(root, "create role reader", "create role staff",
        "grant select on t to role reader",
        "grant role reader to role staff",
        "grant role staff to user ann");
    // ANN is STAFF, and STAFF is READER.
    assertEquals(2, rows(ann, "select * from t").size());
    denied(bob, "select * from t");
    run(root, "revoke role reader from role staff");
    denied(ann, "select * from t");
    // A grant to a role is not a grant to a user of the same name.
    run(root, "grant select on u to role bob");
    denied(bob, "select * from u");
  }

  /** Groups come with the identity; monoql does not keep them. */
  @Test void groups() {
    run(root, "grant select on t to group analysts");
    assertEquals(2, rows(ann, "select * from t").size());
    denied(bob, "select * from t");
    // A group can hold a role, and a grantee of no stated kind can be one.
    run(root, "create role writer", "grant insert on u to role writer",
        "grant role writer to group staff", "grant select on u to staff");
    run(ann, "insert into u values (11)");
    assertEquals(2, rows(ann, "select * from u").size());
    denied(bob, "insert into u values (12)");
    // A group is not a user of the same name, nor the other way about.
    try (Session analysts = root.as(Identity.of("ANALYSTS"))) {
      denied(analysts, "select * from t");
    }
    run(root, "grant delete on t to group bob");
    denied(bob, "delete from t");
    assertEquals(List.of("[user, ANN]", "[groups, ANALYSTS, STAFF]"),
        rows(ann, "select * from metadata.settings"
            + " where setting_name in ('user', 'groups')"));
  }

  /** Sessions derived from one another share catalogs, and nothing
   * else. */
  @Test void derivedSessions() {
    assertEquals("ANN", ann.identity().user());
    assertEquals("S", ann.currentSchema());
    run(root, "create schema other", "use other");
    assertEquals("S", ann.currentSchema());
    // A catalog mounted through one is there for the others; and gone for
    // the others when it is dropped.
    run(root, "create catalog extra with (type = 'memory')",
        "create schema extra.e", "create table extra.e.x (id int)",
        "grant select on extra.e.x to ann");
    assertEquals(0, rows(ann, "select * from extra.e.x").size());
    run(ann, "use extra.e");
    run(root, "drop catalog extra");
    assertEquals(null, ann.currentCatalog());
    // Whoever holds a session may say who it speaks for.
    try (Session nobody = ann.as(null)) {
      assertEquals(null, nobody.identity());
      run(nobody, "drop table c.s.t");
    }
    // Closing a derived session leaves the catalogs open.
    ann.close();
    assertEquals(1, rows(root, "select * from c.s.u").size());
  }

  @Test void grantsOnSchemasAndCatalogs() {
    run(root, "grant select on schema s to ann");
    assertEquals(2, rows(ann, "select * from t").size());
    assertEquals(1, rows(ann, "select * from u").size());
    denied(ann, "insert into t values (3)");

    run(root, "create schema other", "create table other.o (id int)",
        "grant insert on catalog c to bob");
    run(bob, "insert into other.o values (1)", "insert into t values (3)");
    denied(bob, "select * from other.o");
    denied(ann, "select * from other.o");
  }

  /** A view is read with its definer's rights. */
  @Test void views() {
    run(root, "create view v as select id + 1 as n from u",
        "grant select on v to ann");
    assertEquals(List.of("[11]"), rows(ann, "select * from v"));
    denied(ann, "select * from u");
    denied(bob, "select * from v");

    // So you cannot define a view over what you cannot read.
    run(root, "create schema mine", "grant all on schema mine to bob");
    assertEquals("User BOB lacks SELECT on C.S.U",
        denied(bob, "create view mine.peek as select * from s.u"));
    run(root, "grant select on u to bob");
    run(bob, "create view mine.peek as select * from s.u");
    assertEquals(List.of("[10]"), rows(bob, "select * from mine.peek"));
  }

  /** A materialized view is read with a grant on it alone, and refreshed
   * by whoever owns it and can read what it reads. */
  @Test void materializedViews() {
    run(root, "create schema mine", "grant all on schema mine to bob");
    denied(bob, "create materialized view mine.m as select id from s.u");
    run(root, "grant select on u to bob");
    run(bob, "create materialized view mine.m as select id from s.u",
        "grant select on mine.m to ann");
    assertEquals(List.of("[10]"), rows(ann, "select * from mine.m"));
    denied(ann, "select * from u");

    run(root, "insert into u values (11)");
    assertEquals("User ANN lacks OWNERSHIP on C.MINE.M, which writing the"
            + " rows of a materialized view takes",
        denied(ann, "refresh materialized view mine.m"));
    denied(ann, "insert into mine.m values (0)");
    denied(ann, "delete from mine.m");
    assertEquals(List.of("[10]"), rows(ann, "select * from mine.m"));
    run(bob, "refresh materialized view mine.m");
    assertEquals(List.of("[10]", "[11]"),
        rows(ann, "select * from mine.m order by id"));

    // Refreshing reads what the definition reads, as whoever refreshes;
    // reading the rows that are kept does not.
    run(root, "revoke select on u from bob", "insert into u values (12)");
    assertEquals("User BOB lacks SELECT on C.S.U",
        denied(bob, "refresh materialized view mine.m"));
    assertEquals(2, rows(ann, "select * from mine.m").size());
  }

  /** A job runs as whoever submitted it, so nobody submits or changes one
   * in another's name. */
  @Test void jobs() {
    run(root, "create schema mine", "grant all on schema mine to bob",
        "create trigger mine.tr schedule '@daily' as 'go'");
    run(bob, "fire trigger mine.tr");
    assertEquals(List.of("[TR, BOB, go]"),
        rows(root, "select job_name, submitted_by, statement"
            + " from metadata.jobs"));
    final String insert = "insert into metadata.jobs (schema_name, job_name,"
        + " submitted_at, submitted_by, statement) values ('MINE', 'j',"
        + " current_timestamp, ";
    assertEquals("User BOB may submit and change only jobs whose"
            + " SUBMITTED_BY is BOB; this one's is ANN",
        denied(bob, insert + "'ANN', 'select 1')"));
    denied(bob, "insert into metadata.jobs (schema_name, job_name,"
        + " submitted_at, statement) values ('MINE', 'j', current_timestamp,"
        + " 'select 1')");
    run(bob, insert + "current_user, 'select 1')");
    // Owning the schema is not enough to submit one,
    denied(ann, insert + "current_user, 'select 1')");
    // nor to change what someone else's does; though it is to cancel it.
    run(root, "grant all on schema mine to ann");
    denied(ann, "update metadata.jobs set statement = 'select 2'"
        + " where job_name = 'j'");
    assertThrows(CatalogException.class,
        () -> bob.execute("update metadata.jobs set submitted_by = 'ANN'"
            + " where job_name = 'j'"));
    run(bob, "update metadata.jobs set statement = 'select 2'"
        + " where job_name = 'j'");
    run(ann, "delete from metadata.jobs where job_name = 'j'");
    assertEquals(1, rows(root, "select * from metadata.jobs").size());
  }

  /** Creating takes a privilege on the schema; everything after that
   * takes ownership, which creating confers. */
  @Test void creatingAndOwning() {
    assertEquals("User ANN lacks CREATE TABLE on C.S, which changing"
            + " METADATA.TABLES takes",
        denied(ann, "create table mine (id int)"));
    run(root, "grant create table on schema s to ann");
    run(ann, "create table mine (id int)");
    assertEquals(List.of("[OWNERSHIP, TABLE, MINE, USER, ANN, true]"),
        rows(root, "select privilege, object_type, object_name, grantee_type,"
            + " grantee, grantable from metadata.grants"
            + " where object_name = 'MINE'"));

    // It is hers: to use, to change, to share, and to drop.
    run(ann, "insert into mine values (1)", "update mine set id = 2",
        "alter table mine add column name varchar",
        "comment on table mine is 'ann''s'",
        "grant select on mine to bob");
    assertEquals(List.of("[2, null]"), rows(ann, "select * from mine"));
    assertEquals(List.of("[2, null]"), rows(bob, "select * from mine"));

    // CREATE TABLE is that and no more.
    assertEquals("User ANN lacks OWNERSHIP on C.S.T, which changing"
            + " METADATA.TABLES takes",
        denied(ann, "drop table t"));
    denied(ann, "alter table t add column name varchar");
    denied(ann, "select * from t");
    denied(ann, "grant select on t to bob");
    assertEquals("User ANN lacks CREATE VIEW on C.S, which changing"
            + " METADATA.VIEWS takes",
        denied(ann, "create view w as select 1 as c"));
    denied(ann, "create schema more");
    denied(ann, "create role r");
    // And what is hers is not Bob's, whatever he may read.
    denied(bob, "drop table mine");
    denied(bob, "alter table mine drop column name");
    denied(bob, "insert into mine values (3, 'x')");
    denied(bob, "create table his (id int)");

    // All privileges on a table are not the table.
    run(root, "grant all on t to bob");
    run(bob, "insert into t values (3)");
    denied(bob, "drop table t");
    denied(bob, "grant select on t to ann");

    run(ann, "drop table mine");
    assertEquals(List.of(),
        rows(root, "select * from metadata.grants"
            + " where object_name = 'MINE'"));
  }

  /** The other spelling of a statement is held to the same rules, and has
   * the same consequences. */
  @Test void theDmlIsTheStatement() {
    denied(ann, "delete from metadata.tables where table_name = 'T'");
    denied(ann, "insert into metadata.roles (role_name) values ('R')");
    denied(ann, "insert into metadata.views (schema_name, view_name,"
        + " definition) values ('S', 'W', 'SELECT 1 AS C')");
    run(root, "grant create view on schema s to ann");
    run(ann, "insert into metadata.views (schema_name, view_name,"
        + " definition) values ('S', 'W', 'SELECT 1 AS C')");
    assertEquals(List.of("[1]"), rows(ann, "select * from w"));
    denied(bob, "delete from metadata.views where view_name = 'W'");
    denied(bob, "create or replace view w as select 2 as c");
    run(ann, "create or replace view w as select 2 as c",
        "delete from metadata.views where view_name = 'W'");
  }

  @Test void schemasAndCatalogs() {
    denied(ann, "create schema hers");
    run(root, "grant create schema on catalog c to ann");
    run(ann, "create schema hers", "create table hers.h (id int)",
        "create view hers.v as select * from hers.h",
        "grant select on schema hers to bob");
    assertEquals(0, rows(bob, "select * from hers.v").size());
    denied(bob, "create table hers.b (id int)");
    denied(ann, "create table x (id int)"); // S is not hers
    denied(ann, "drop schema s");
    run(ann, "drop view hers.v", "drop table hers.h", "drop schema hers");
    assertEquals(List.of(),
        rows(root, "select * from metadata.grants"
            + " where object_schema = 'HERS'"));

    // ALL PRIVILEGES on a schema or a catalog does include owning it.
    run(root, "grant all on schema s to ann");
    run(ann, "create table x (id int)", "drop table t",
        "grant select on u to bob");
    assertEquals("User ANN lacks OWNERSHIP on C, which changing"
            + " METADATA.ROLES takes",
        denied(ann, "create role r"));
    run(root, "grant all on catalog c to bob");
    run(bob, "create schema more", "create role r", "grant role r to ann",
        "drop table x", "drop schema more");
  }

  /** Grants belong to an object, not to its name. */
  @Test void grantsFollowObjects() {
    run(root, "grant select on t to bob", "drop table t",
        "create table t (id int)");
    denied(bob, "select * from t");
    assertEquals(List.of(), rows(root, "select * from metadata.grants"));

    run(root, "create schema other", "grant create table on schema s to ann",
        "grant select on u to bob");
    run(ann, "create table mine (id int)", "grant select on mine to bob");
    // Moving a table into a schema is creating one there.
    denied(ann, "alter table mine rename to other.elsewhere");
    run(ann, "alter table mine rename to yours");
    assertEquals(0, rows(bob, "select * from yours").size());
    run(ann, "drop table yours");
    // A session with no user owns nothing, and needs to own nothing.
    assertEquals(List.of("[CREATE TABLE, null, ANN]", "[SELECT, U, BOB]"),
        rows(root, "select privilege, object_name, grantee"
            + " from metadata.grants order by 1"));
  }

  /** A grant to write may say what the rows written may be read from. */
  @Test void sources() {
    run(root, "create table w (id int)", "create table x (id int)",
        "grant select on schema s to ann",
        "grant insert on w using t to ann");
    run(ann, "insert into w select id from t",
        "insert into w select t.id from t join t as again on true");
    assertEquals("User ANN lacks INSERT on C.S.W of what is read from C.S.U",
        denied(ann, "insert into w select id from u"));
    // Wherever in the statement it is read, and whatever is made of it.
    denied(ann, "insert into w select t.id from t join u on t.id = u.id");
    denied(ann, "insert into w select id from t"
        + " where id in (select id - 9 from u)");
    denied(ann, "insert into w select count(*) from u");
    // Nor is it explained.
    assertThrows(AccessDeniedException.class,
        () -> ann.explain("insert into w select id from u"));
    // What the statement says itself is its author's to write, and so is
    // what it reads from the table it writes.
    run(ann, "insert into w values (7)", "insert into w select id from w");
    // INSERT is not DELETE, and W is not X.
    denied(ann, "delete from w where id in (select id from t)");
    denied(ann, "insert into x select id from t");

    // A source may be a schema or a catalog; no source is any source.
    run(root, "create schema other", "create table other.o (id int)",
        "grant select on schema other to ann",
        "grant delete on w using schema other to ann",
        "grant update on w using catalog c to ann",
        "grant insert on x to ann");
    run(ann, "delete from w where id in (select id from other.o)",
        "update w set id = (select max(id) from u)",
        "update w set id = (select max(id) from other.o)",
        "insert into x select id from u");
    denied(ann, "delete from w where id in (select id from u)");

    // A grant on a schema, of a source.
    run(root, "grant insert on schema other using u to bob",
        "grant select on schema s to bob");
    run(bob, "insert into other.o select id from u");
    denied(bob, "insert into other.o select id from t");

    // Revoking the grant that has no source leaves the ones that have;
    // and the other way about.
    run(root, "grant insert on w to ann", "revoke insert on w from ann");
    run(ann, "insert into w select id from t");
    denied(ann, "insert into w select id from u");
    run(root, "grant insert on w to ann",
        "revoke insert on w using t from ann");
    run(ann, "insert into w select id from t");
    assertEquals(
        List.of("[DELETE, W, C, OTHER, null]", "[INSERT, W, null, null, null]",
            "[UPDATE, W, C, null, null]"),
        rows(root, "select privilege, object_name, source_catalog,"
            + " source_schema, source_name from metadata.grants"
            + " where object_name = 'W' order by 1"));
  }

  /** What a view reads is read by whoever reads the view. */
  @Test void sourcesThroughViews() {
    run(root, "create table w (id int)",
        "create view v as select id from u",
        "create view vv as select v.id from v join t on true",
        "create materialized view m as select id from u",
        "grant select on schema s to ann",
        "grant insert on w using t to ann");
    assertEquals("User ANN lacks INSERT on C.S.W of what is read from C.S.V",
        denied(ann, "insert into w select id from v"));
    denied(ann, "insert into w select id from vv");
    denied(ann, "insert into w select id from m");
    // Either by a grant of what it reads,
    run(root, "grant insert on w using u to ann");
    run(ann, "insert into w select id from v",
        "insert into w select id from vv",
        "insert into w select id from m");
    // or by one of the view, which says nothing of what it reads.
    run(root, "revoke insert on w using u from ann",
        "grant insert on w using v to ann");
    run(ann, "insert into w select id from v",
        "insert into w select id from vv");
    denied(ann, "insert into w select id from u");
    denied(ann, "insert into w select id from m");
  }

  /** A grant goes with its source, and follows it when it is renamed. */
  @Test void grantsFollowSources() {
    run(root, "create table w (id int)", "create schema other",
        "create table other.o (id int)",
        "grant select on schema s to ann", "grant select on schema other to ann",
        "grant insert on w using u to ann",
        "grant insert on w using other.o to ann",
        "grant insert on w using schema other to bob");
    run(root, "alter table u rename to u2");
    run(ann, "insert into w select id from u2");
    run(root, "drop table u2", "create table u2 (id int)",
        "create table u (id int)");
    denied(ann, "insert into w select id from u2");
    denied(ann, "insert into w select id from u");
    // The table that is written to is renamed: the source stays.
    run(root, "alter table w rename to w2");
    run(ann, "insert into w2 select id from other.o");
    run(root, "drop table other.o", "drop schema other");
    assertEquals(List.of(),
        rows(root, "select grantee from metadata.grants"
            + " where source_catalog is not null"));
  }

  @Test void grantOptionOfASource() {
    run(root, "create table w (id int)",
        "grant insert on w using schema s to ann with grant option");
    run(ann, "grant insert on w using t to bob",
        "grant insert on w using schema s to bob");
    assertEquals("User ANN may not grant or revoke INSERT on C.S.W: that"
            + " takes OWNERSHIP of it, or INSERT WITH GRANT OPTION",
        denied(ann, "grant insert on w to bob"));
    assertEquals("User ANN may not grant or revoke INSERT on C.S.W of what"
            + " is read from C: that takes OWNERSHIP of it, or INSERT WITH"
            + " GRANT OPTION",
        denied(ann, "grant insert on w using catalog c to bob"));
    denied(ann, "grant delete on w using t to bob");
    run(ann, "revoke insert on w using t from bob");
    // The row is the statement, and is held to the same rules.
    assertThrows(CatalogException.class,
        () -> root.execute("insert into metadata.grants (privilege,"
            + " object_type, object_schema, object_name, grantee_type,"
            + " grantee, grantable, source_catalog, source_schema)"
            + " values ('SELECT', 'TABLE', 'S', 'W', 'USER', 'BOB', false,"
            + " 'C', 'S')"));
  }

  @Test void grantOption() {
    run(root, "grant select on t to ann", "grant insert on t to ann");
    assertEquals("User ANN may not grant or revoke SELECT on C.S.T: that takes"
            + " OWNERSHIP of it, or SELECT WITH GRANT OPTION",
        denied(ann, "grant select on t to bob"));
    run(root, "grant select on t to ann with grant option");
    run(ann, "grant select on t to bob");
    assertEquals(2, rows(bob, "select * from t").size());
    // Only what was granted that way, and only where.
    denied(ann, "grant insert on t to bob");
    denied(ann, "grant select on u to bob");
    denied(ann, "grant select on schema s to bob");
    denied(ann, "insert into metadata.grants (privilege, object_type,"
        + " object_schema, object_name, grantee_type, grantee, grantable)"
        + " values ('DELETE', 'TABLE', 'S', 'T', 'USER', 'BOB', false)");
    run(ann, "revoke select on t from bob");
    denied(bob, "select * from t");
  }

  @Test void whatIsOpen() {
    // Metadata can be read by anyone.
    assertEquals(List.of("[T, null]", "[U, null]"), rows(ann, "show tables"));
    assertEquals(1, rows(ann, "describe t").size());
    assertEquals(List.of("[ANN]"),
        rows(ann, "select setting_value from metadata.settings"
            + " where setting_name = 'user'"));
    assertEquals(List.of("[ANN]"), rows(ann, "select current_user"));
    assertEquals(List.of("[" + System.getProperty("user.name") + "]"),
        rows(root, "select current_user"));
    // So can queries that touch no table.
    assertEquals(List.of("[2]"), rows(ann, "select 1 + 1"));
    // Sessions keep their own settings; but not who they are.
    run(root, "create schema other");
    run(ann, "use other", "use c.s");
    final CatalogException e =
        assertThrows(CatalogException.class,
            () -> ann.execute("update metadata.settings"
                + " set setting_value = 'BOB' where setting_name = 'user'"));
    assertTrue(e.getMessage().contains("cannot be changed"), e.getMessage());
    // Mounting a catalog would be a way around all of this.
    denied(ann, "create catalog extra with (type = 'memory')");
    denied(ann, "drop catalog c");
  }

  /** A catalog with no GRANTS table is not monoql's to police. */
  @Test void catalogWithoutGrants() {
    final MemoryCatalog ungoverned = new MemoryCatalog() {
      @Override public MetaTable<Grant> grants() {
        return MetaTable.unsupported("GRANTS");
      }
    };
    try (Session setup = Monoql.builder().catalog("X", ungoverned).build()) {
      run(setup, "create schema s", "create table s.t (id int)");
    }
    try (Session session =
        Monoql.builder().catalog("X", ungoverned)
            .identity(Identity.of("ANN")).build()) {
      run(session, "insert into x.s.t values (1)", "create table x.s.u (i int)");
      assertEquals(List.of("[1]"), rows(session, "select * from x.s.t"));
    }
  }
}
