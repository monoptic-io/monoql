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
package org.apache.calcite.test;

import org.apache.calcite.rel.RelReferentialConstraint;
import org.apache.calcite.rel.RelReferentialConstraintImpl;
import org.apache.calcite.schema.QueryableTable;
import org.apache.calcite.schema.TranslatableTable;
import org.apache.calcite.test.schemata.hr.Department;
import org.apache.calcite.test.schemata.hr.DepartmentPlus;
import org.apache.calcite.test.schemata.hr.Dependent;
import org.apache.calcite.test.schemata.hr.Employee;
import org.apache.calcite.test.schemata.hr.Event;
import org.apache.calcite.test.schemata.hr.Location;
import org.apache.calcite.test.schemata.hr.NullableTest;
import org.apache.calcite.util.Smalls;
import org.apache.calcite.util.mapping.IntPair;
import com.google.common.collect.ImmutableList;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Collections;

// monoql: was nested in MaterializationTest, which is gone.
/**
 * Hr schema with FK-UK relationship.
 */
public class HrFKUKSchema {
  @Override public String toString() {
    return "HrFKUKSchema";
  }

  public final Employee[] emps = {
      new Employee(100, 10, "Bill", 10000, 1000),
      new Employee(200, 20, "Eric", 8000, 500),
      new Employee(150, 10, "Sebastian", 7000, null),
      new Employee(110, 10, "Theodore", 10000, 250),
  };
  public final Department[] depts = {
      new Department(10, "Sales", Arrays.asList(emps[0], emps[2], emps[3]),
          new Location(-122, 38)),
      new Department(30, "Marketing", ImmutableList.of(),
          new Location(0, 52)),
      new Department(20, "HR", Collections.singletonList(emps[1]), null),
  };
  public final DepartmentPlus[] depts2 = {
      new DepartmentPlus(10, "Sales", Arrays.asList(emps[0], emps[2], emps[3]),
          new Location(-122, 38), new Timestamp(0)),
      new DepartmentPlus(30, "Marketing", ImmutableList.of(),
          new Location(0, 52), new Timestamp(0)),
      new DepartmentPlus(20, "HR", Collections.singletonList(emps[1]),
          null, new Timestamp(0)),
  };
  public final Dependent[] dependents = {
      new Dependent(10, "Michael"),
      new Dependent(10, "Jane"),
  };
  public final NullableTest[] nullables = {
      new NullableTest(null, null, 1),
  };
  public final Dependent[] locations = {
      new Dependent(10, "San Francisco"),
      new Dependent(20, "San Diego"),
  };
  public final Event[] events = {
      new Event(100, new Timestamp(0)),
      new Event(200, new Timestamp(0)),
      new Event(150, new Timestamp(0)),
      new Event(110, null),
  };

  public final RelReferentialConstraint rcs0 =
      RelReferentialConstraintImpl.of(
          ImmutableList.of("hr", "emps"), ImmutableList.of("hr", "depts"),
          ImmutableList.of(IntPair.of(1, 0)));

  public QueryableTable foo(int count) {
    return Smalls.generateStrings(count);
  }

  public TranslatableTable view(String s) {
    return Smalls.view(s);
  }

  public TranslatableTable matview() {
    return Smalls.strView("noname");
  }
}
