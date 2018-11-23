/*
 * Licensed to Crate under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.  Crate licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial
 * agreement.
 */

package io.crate.metadata.settings.session;

import com.google.common.collect.ImmutableMap;
import io.crate.types.DataType;
import io.crate.action.sql.SessionContext;
import io.crate.types.DataTypes;

import java.util.Map;

import static io.crate.metadata.SearchPath.createSearchPathFrom;

public class SessionSettingRegistry {

    public static final String SEARCH_PATH_KEY = "search_path";
    public static final String SEMI_JOIN_KEY = "enable_semijoin";
    public static final String HASH_JOIN_KEY = "enable_hashjoin";

    public static final Map<String, SessionSetting<?>> SETTINGS = ImmutableMap.<String, SessionSetting<?>>builder()
            .put(SEARCH_PATH_KEY,
                new SessionSetting<>(
                    DataTypes.STRING_ARRAY,
                    objects -> {}, // everything allowed, empty list (resulting by ``SET .. TO DEFAULT`` results in defaults
                    objects -> createSearchPathFrom(objectsToStringArray(objects)),
                    SessionContext::setSearchPath,
                    SessionContext::searchPath
                    ))
            .put(SEMI_JOIN_KEY,
                new SessionSetting<>(
                    DataTypes.BOOLEAN,
                    objects -> {
                        if (objects.length != 1) {
                            throw new IllegalArgumentException(HASH_JOIN_KEY + " should have only one argument.");
                        }
                    },
                    objects -> DataTypes.BOOLEAN.value(objects[0]),
                    SessionContext::setSemiJoinsRewriteEnabled,
                    SessionContext::getSemiJoinsRewriteEnabled
                ))
            .put(HASH_JOIN_KEY,
                new SessionSetting<>(
                    DataTypes.BOOLEAN,
                    objects -> {
                        if (objects.length != 1) {
                            throw new IllegalArgumentException(HASH_JOIN_KEY + " should have only one argument.");
                        }
                    },
                    objects -> DataTypes.BOOLEAN.value(objects[0]),
                    SessionContext::setHashJoinEnabled,
                    SessionContext::isHashJoinEnabled
                ))
            .build();

    private static String[] objectsToStringArray(Object[] objects) {
        String[] strings = new String[objects.length];
        for (int i = 0; i < objects.length; i++) {
            strings[i] = DataTypes.STRING.value(objects[i]);
        }
        return strings;
    }
}
