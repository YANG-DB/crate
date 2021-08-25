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

package io.crate.execution.engine.aggregation.impl.average;

import io.crate.Streamer;
import io.crate.types.DataType;
import io.crate.types.FixedWidthType;
import org.elasticsearch.common.io.stream.StreamOutput;

import java.io.IOException;

public abstract class AverageStateType extends DataType<AverageState> implements FixedWidthType, Streamer<AverageState> {

    @Override
    public Precedence precedence() {
        return Precedence.CUSTOM;
    }

    @Override
    public Streamer<AverageState> streamer() {
        return this;
    }

    @Override
    public int compare(AverageState val1, AverageState val2) {
        if (val1 == null) return -1;
        return val1.compareTo(val2);
    }

    @Override
    public void writeValueTo(StreamOutput out, AverageState v) throws IOException {
        v.writeTo(out);
    }
}
