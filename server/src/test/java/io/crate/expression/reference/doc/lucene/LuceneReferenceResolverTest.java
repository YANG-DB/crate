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

package io.crate.expression.reference.doc.lucene;

import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.assertThat;

import java.util.List;

import org.elasticsearch.index.mapper.KeywordFieldMapper.KeywordFieldType;
import org.elasticsearch.test.ESTestCase;
import org.junit.Test;

import io.crate.expression.symbol.DynamicReference;
import io.crate.metadata.Reference;
import io.crate.metadata.ReferenceIdent;
import io.crate.metadata.RelationName;
import io.crate.metadata.RowGranularity;
import io.crate.metadata.SimpleReference;
import io.crate.sql.tree.ColumnPolicy;
import io.crate.types.DataTypes;

public class LuceneReferenceResolverTest extends ESTestCase {

    // just return any fieldType to get passt the null check
    private RelationName name = new RelationName("s", "t");
    private LuceneReferenceResolver luceneReferenceResolver = new LuceneReferenceResolver(
        name.indexNameOrAlias(),
        i -> new KeywordFieldType("dummy", true, false),
        List.of()
    );

    @Test
    public void testGetImplementationWithColumnsOfTypeCollection() {
        SimpleReference arrayRef = new SimpleReference(
            new ReferenceIdent(name, "a"), RowGranularity.DOC, DataTypes.DOUBLE_ARRAY, 0, null
        );
        assertThat(luceneReferenceResolver.getImplementation(arrayRef),
            instanceOf(DocCollectorExpression.ChildDocCollectorExpression.class));
    }

    @Test
    public void testGetImplementationForSequenceNumber() {
        SimpleReference seqNumberRef = new SimpleReference(
            new ReferenceIdent(name, "_seq_no"), RowGranularity.DOC, DataTypes.LONG, 0, null
        );
        assertThat(luceneReferenceResolver.getImplementation(seqNumberRef), instanceOf(SeqNoCollectorExpression.class));
    }

    @Test
    public void testGetImplementationForPrimaryTerm() {
        SimpleReference primaryTerm = new SimpleReference(
            new ReferenceIdent(name, "_primary_term"), RowGranularity.DOC, DataTypes.LONG, 0, null
        );
        assertThat(luceneReferenceResolver.getImplementation(primaryTerm),
                   instanceOf(PrimaryTermCollectorExpression.class));
    }

    @Test
    public void test_ignored_dynamic_references_are_resolved_using_sourcelookup() {
        Reference ignored = new DynamicReference(
            new ReferenceIdent(name, "a", List.of("b")), RowGranularity.DOC, ColumnPolicy.IGNORED, 0);

        assertThat(luceneReferenceResolver.getImplementation(ignored),
                   instanceOf(DocCollectorExpression.ChildDocCollectorExpression.class));
    }
}
