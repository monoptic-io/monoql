/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.plan;

import org.apache.calcite.config.CalciteSystemProperty;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelShuttleImpl;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.logical.LogicalJoin;
import org.apache.calcite.rel.metadata.DefaultRelMetadataProvider;
import org.apache.calcite.rel.rules.CoreRules;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexUtil;
import org.apache.calcite.schema.Table;
import org.apache.calcite.sql.SqlExplainFormat;
import org.apache.calcite.sql.SqlExplainLevel;
import org.apache.calcite.tools.Program;
import org.apache.calcite.tools.Programs;
import org.apache.calcite.util.Util;
import org.apache.calcite.util.mapping.Mappings;

import com.google.common.collect.ImmutableList;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.apache.calcite.linq4j.Nullness.castNonNull;

import static java.util.Objects.requireNonNull;

/**
 * Records that a particular query is materialized by a particular table.
 */
public class RelOptMaterialization {
  public final RelNode tableRel;
  public final List<String> qualifiedTableName;
  public final RelNode queryRel;

  /**
   * Creates a RelOptMaterialization.
   */
  // monoql: no star table; lattices are gone.
  public RelOptMaterialization(RelNode tableRel, RelNode queryRel,
      List<String> qualifiedTableName) {
    this.queryRel = requireNonNull(queryRel, "queryRel");
    this.tableRel =
        RelOptUtil.createCastRel(requireNonNull(tableRel, "tableRel"),
            queryRel.getRowType(), false);
    this.qualifiedTableName = qualifiedTableName;
  }

  /**
   * Converts a relational expression to a form where
   * {@link org.apache.calcite.rel.logical.LogicalJoin}s are
   * as close to leaves as possible.
   */
  public static RelNode toLeafJoinForm(RelNode rel) {
    final Program program =
        Programs.hep(
            ImmutableList.of(CoreRules.JOIN_PROJECT_RIGHT_TRANSPOSE,
                CoreRules.JOIN_PROJECT_LEFT_TRANSPOSE,
                CoreRules.FILTER_INTO_JOIN,
                CoreRules.PROJECT_REMOVE,
                CoreRules.PROJECT_MERGE),
        false,
        DefaultRelMetadataProvider.INSTANCE);
    if (CalciteSystemProperty.DEBUG.value()) {
      System.out.println(
          RelOptUtil.dumpPlan("before", rel, SqlExplainFormat.TEXT,
              SqlExplainLevel.DIGEST_ATTRIBUTES));
    }
    final RelNode rel2 =
        program.run(castNonNull(null), rel, castNonNull(null),
            ImmutableList.of());
    if (CalciteSystemProperty.DEBUG.value()) {
      System.out.println(
          RelOptUtil.dumpPlan("after", rel2, SqlExplainFormat.TEXT,
              SqlExplainLevel.DIGEST_ATTRIBUTES));
    }
    return rel2;
  }
}
