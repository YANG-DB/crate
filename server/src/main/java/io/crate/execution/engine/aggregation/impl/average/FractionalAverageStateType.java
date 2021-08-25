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

import org.apache.lucene.util.RamUsageEstimator;
import org.elasticsearch.common.io.stream.StreamInput;

import java.io.IOException;

public class FractionalAverageStateType extends AverageStateType {

    static final int ID = 1026;
    static final FractionalAverageStateType INSTANCE = new FractionalAverageStateType();
    private static final int FRACTIONAL_AVERAGE_STATE_SIZE = (int) RamUsageEstimator.shallowSizeOfInstance(FractionalAverageStateType.class);

    @Override
    public int id() {
        return ID;
    }

    @Override
    public String getName() {
        return "fractional_average_state";
    }

    @Override
    public AverageState sanitizeValue(Object value) {
        return (FractionalAverageState) value;
    }

    @Override
    public AverageState readValueFrom(StreamInput in) throws IOException {
        AverageState averageState = new FractionalAverageState();
        averageState.sum = in.readDouble();
        averageState.count = in.readVLong();
        return averageState;
    }

    @Override
    public int fixedSize() {
        return FRACTIONAL_AVERAGE_STATE_SIZE;
    }
}

