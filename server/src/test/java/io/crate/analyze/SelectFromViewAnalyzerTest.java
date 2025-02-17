/*
 * Licensed to Crate.io GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.analyze;

import static io.crate.testing.RelationMatchers.isDocTable;
import static io.crate.testing.SymbolMatchers.isField;
import static io.crate.testing.SymbolMatchers.isReference;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.assertThat;

import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;

import io.crate.analyze.relations.AnalyzedView;
import io.crate.metadata.RelationName;
import io.crate.test.integration.CrateDummyClusterServiceUnitTest;
import io.crate.testing.SQLExecutor;

public class SelectFromViewAnalyzerTest extends CrateDummyClusterServiceUnitTest {

    private SQLExecutor e;

    @Before
    public void setUpExecutor() throws Exception {
        e = SQLExecutor.builder(clusterService)
            .addTable("create table doc.t1 (name string, x int)")
            .addView(new RelationName("doc", "v1"), "select name, count(*) from doc.t1 group by name")
            .build();
    }

    @Test
    public void testSelectFromViewIsResolvedToViewQueryDefinition() {
        QueriedSelectRelation query = e.analyze("select * from doc.v1");
        assertThat(query.outputs(), contains(isField("name"), isField("count(*)")));
        assertThat(query.groupBy(), Matchers.empty());
        assertThat(query.from(), contains(instanceOf(AnalyzedView.class)));
        QueriedSelectRelation queriedDocTable = (QueriedSelectRelation) ((AnalyzedView) query.from().get(0)).relation();
        assertThat(queriedDocTable.groupBy(), contains(isReference("name")));
        assertThat(queriedDocTable.from(), contains(isDocTable(new RelationName("doc", "t1"))));
    }
}
