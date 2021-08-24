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

import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import javax.annotation.Nullable;

import io.crate.execution.engine.aggregation.impl.AggregationImplModule;

import org.apache.lucene.util.NumericUtils;
import org.apache.lucene.util.RamUsageEstimator;
import org.elasticsearch.Version;
import org.elasticsearch.index.mapper.MappedFieldType;

import io.crate.breaker.RamAccounting;
import io.crate.common.collections.Lists2;
import io.crate.data.Input;
import io.crate.execution.engine.aggregation.AggregationFunction;
import io.crate.execution.engine.aggregation.DocValueAggregator;
import io.crate.execution.engine.aggregation.impl.templates.SortedNumericDocValueAggregator;
import io.crate.expression.symbol.Literal;
import io.crate.expression.symbol.Symbols;
import io.crate.memory.MemoryManager;
import io.crate.metadata.Reference;
import io.crate.metadata.doc.DocTableInfo;
import io.crate.metadata.functions.Signature;
import io.crate.types.ByteType;
import io.crate.types.DataType;
import io.crate.types.DataTypes;
import io.crate.types.DoubleType;
import io.crate.types.FloatType;
import io.crate.types.IntegerType;
import io.crate.types.LongType;
import io.crate.types.ShortType;
import io.crate.types.TimestampType;

public class AverageAggregation extends AggregationFunction<AverageState, Double> {

    public static final String[] NAMES = new String[]{"avg", "mean"};
    public static final String NAME = NAMES[0];

    static {
        DataTypes.register(AverageStateType.ID, in -> AverageStateType.INSTANCE);
    }

    static final List<DataType<?>> SUPPORTED_TYPES = Lists2.concat(
        DataTypes.NUMERIC_PRIMITIVE_TYPES, DataTypes.TIMESTAMPZ);

    /**
     * register as "avg" and "mean"
     */
    public static void register(AggregationImplModule mod) {
        for (var functionName : NAMES) {
            for (var supportedType : SUPPORTED_TYPES) {
                mod.register(
                    Signature.aggregate(
                        functionName,
                        supportedType.getTypeSignature(),
                        DataTypes.DOUBLE.getTypeSignature()),
                    AverageAggregation :: new
                );
            }
        }
    }

    private final Signature signature;
    private final Signature boundSignature;

    AverageAggregation(Signature signature, Signature boundSignature) {
        this.signature = signature;
        this.boundSignature = boundSignature;
    }

    @Override
    public AverageState iterate(RamAccounting ramAccounting,
                                MemoryManager memoryManager,
                                AverageState state,
                                Input... args) {
        if (state != null) {
            Number value = (Number) args[0].value();
            if (value != null) {
                state.addNumber(value); // Mutates state.
            }
        }
        return state;
    }

    @Override
    public boolean isRemovableCumulative() {
        return true;
    }

    @Override
    public AverageState removeFromAggregatedState(RamAccounting ramAccounting,
                                                  AverageState previousAggState,
                                                  Input[] stateToRemove) {
        if (previousAggState != null) {
            Number value = (Number) stateToRemove[0].value();
            if (value != null) {
                previousAggState.removeNumber(value); // Mutates previousAggState.
            }
        }
        return previousAggState;
    }

    @Override
    public AverageState reduce(RamAccounting ramAccounting, AverageState state1, AverageState state2) {
        if (state1 == null) {
            return state2;
        }
        if (state2 == null) {
            return state1;
        }
        state1.reduce(state2); // Mutates state1.
        return state1;
    }

    @Override
    public Double terminatePartial(RamAccounting ramAccounting, AverageState state) {
        return state.value();
    }

    @Nullable
    @Override
    public AverageState newState(RamAccounting ramAccounting,
                                 Version indexVersionCreated,
                                 Version minNodeInCluster,
                                 MemoryManager memoryManager) {
        var dataType = signature.getArgumentDataTypes().get(0);
        switch (dataType.id()) {
            case DoubleType.ID:
            case FloatType.ID:
                ramAccounting.addBytes(RamUsageEstimator.shallowSizeOfInstance(FractionalAverageState.class));
                return new FractionalAverageState();
            case LongType.ID:
            case IntegerType.ID:
            case ShortType.ID:
            case ByteType.ID:
            case TimestampType.ID_WITH_TZ:
                ramAccounting.addBytes(RamUsageEstimator.shallowSizeOfInstance(IntegralAverageState.class));
                return new IntegralAverageState();
            default:
                throw new IllegalArgumentException(String.format(Locale.ENGLISH, "data type %s is not supported", dataType.getName()));
        }
    }

    @Override
    public DataType<?> partialType() {
        return AverageStateType.INSTANCE;
    }

    @Override
    public Signature signature() {
        return signature;
    }

    @Override
    public Signature boundSignature() {
        return boundSignature;
    }

    @Nullable
    @Override
    public DocValueAggregator<?> getDocValueAggregator(List<Reference> aggregationReferences,
                                                       Function<List<String>, List<MappedFieldType>> getMappedFieldTypes,
                                                       DocTableInfo table,
                                                       List<Literal<?>> optionalParams) {
        var fieldTypes = getMappedFieldTypes.apply(
            Lists2.map(aggregationReferences, s -> ((Reference)s).column().fqn())
        );
        if (fieldTypes == null) {
            return null;
        }
        var argumentTypes = Symbols.typeView(aggregationReferences);
        switch (argumentTypes.get(0).id()) {
            case ByteType.ID:
            case ShortType.ID:
            case IntegerType.ID:
            case LongType.ID:
                return new SortedNumericDocValueAggregator<>(
                    fieldTypes.get(0).name(),
                    (ramAccounting, memoryManager, minNodeVersion) -> {
                        ramAccounting.addBytes(RamUsageEstimator.shallowSizeOfInstance(IntegralAverageState.class));
                        return new IntegralAverageState();
                    },
                    (values, state) -> {
                        state.addNumber(values.nextValue()); // Mutates state.
                    }
                );
            case FloatType.ID:
                return new SortedNumericDocValueAggregator<>(
                    fieldTypes.get(0).name(),
                    (ramAccounting, memoryManager, minNodeVersion) -> {
                        ramAccounting.addBytes(RamUsageEstimator.shallowSizeOfInstance(FractionalAverageState.class));
                        return new FractionalAverageState();
                    },
                    (values, state) -> {
                        var value = NumericUtils.sortableIntToFloat((int) values.nextValue());
                        state.addNumber(value); // Mutates state.
                    }
                );
            case DoubleType.ID:
                return new SortedNumericDocValueAggregator<>(
                    fieldTypes.get(0).name(),
                    (ramAccounting, memoryManager, minNodeVersion) -> {
                        ramAccounting.addBytes(RamUsageEstimator.shallowSizeOfInstance(FractionalAverageState.class));
                        return new FractionalAverageState();
                    },
                    (values, state) -> {
                        var value = NumericUtils.sortableLongToDouble((values.nextValue()));
                        state.addNumber(value); // Mutates state.
                    }
                );
            default:
                return null;
        }
    }
}

