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

import io.crate.types.ByteType;
import io.crate.types.DataType;
import io.crate.types.DoubleType;
import io.crate.types.FloatType;
import io.crate.types.IntegerType;
import io.crate.types.LongType;
import io.crate.types.ShortType;
import io.crate.types.TimestampType;

import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.Locale;
import java.util.Objects;

public abstract class AverageState implements Comparable<AverageState>, Writeable {

    protected double sum = 0;
    protected long count = 0L;

    public static AverageState of(DataType<?> dataType) {
        switch (dataType.id()) {
            case DoubleType.ID:
            case FloatType.ID:
                return new NonIntegralAverageState();
            case LongType.ID:
            case IntegerType.ID:
            case ShortType.ID:
            case ByteType.ID:
            case TimestampType.ID_WITH_TZ:
                return new IntegralAverageState();
            default:
                throw new IllegalArgumentException(String.format(Locale.ENGLISH, "data type %s is not supported", dataType.getName()));
        }
    }

    public Double value() {
        if (count > 0) {
            return sum / count;
        } else {
            return null;
        }
    }

    public abstract void addNumber(Number number);

    public abstract void removeNumber(Number number);

    public abstract void reduce(@Nonnull AverageState other);

    @Override
    public int compareTo(AverageState o) {
        if (o == null) {
            return 1;
        } else {
            int compare = Double.compare(sum, o.sum);
            if (compare == 0) {
                return Long.compare(count, o.count);
            }
            return compare;
        }
    }

    @Override
    public String toString() {
        return "sum: " + sum + " count: " + count;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        AverageState that = (AverageState) o;
        return Objects.equals(that.value(), value());
    }

    @Override
    public int hashCode() {
        return Objects.hash(value());
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        // Similar to HllState but in order not to store dataType in state we provide minimal information for later reading from StreamInput.
        out.writeBoolean(this instanceof IntegralAverageState);
        out.writeDouble(this.sum);
        out.writeVLong(this.count);
    }
}
