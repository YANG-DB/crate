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

package io.crate.license;

import static org.elasticsearch.gateway.GatewayMetaStateTests.randomMetadata;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.core.Is.is;

import java.util.EnumSet;
import java.util.List;

import org.elasticsearch.Version;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.gateway.GatewayMetaState;
import org.elasticsearch.gateway.GatewayMetaStateTests;
import org.elasticsearch.plugins.MetadataUpgrader;
import org.elasticsearch.test.TestCustomMetadata;
import org.junit.Test;
import org.junit.runner.RunWith;

import com.carrotsearch.randomizedtesting.RandomizedRunner;
import com.carrotsearch.randomizedtesting.annotations.ThreadLeakScope;

import io.crate.metadata.CustomMetadataUpgraderLoader;


@RunWith(RandomizedRunner.class)
@ThreadLeakScope(ThreadLeakScope.Scope.NONE)
public class LicenseCustomMetadataUpgraderTest {

    @Test
    public void test_remove_license_custom_metadata() {
        var metadata = randomMetadata(new LicenseCustomMetaData("license_key"));
        var customMetadataUpgraderLoader = new CustomMetadataUpgraderLoader(Settings.EMPTY);
        var metadataUpgrader = new MetadataUpgrader(
            List.of(customs -> customMetadataUpgraderLoader.apply(customs)),
            List.of()
        );
        var upgrade = GatewayMetaState.upgradeMetadata(metadata, new GatewayMetaStateTests.MockMetadataIndexUpgradeService(false), metadataUpgrader);
        assertThat(upgrade != metadata, is(true));
        assertThat(Metadata.isGlobalStateEquals(upgrade, metadata), is(false));
        assertThat(upgrade.custom(LicenseCustomMetaData.TYPE), is(nullValue()));
    }

    private static class LicenseCustomMetaData extends TestCustomMetadata {
        public static final String TYPE = License.WRITEABLE_TYPE;

        protected LicenseCustomMetaData(String data) {
            super(data);
        }

        @Override
        public String getWriteableName() {
            return TYPE;
        }

        @Override
        public Version getMinimalSupportedVersion() {
            return Version.CURRENT;
        }

        @Override
        public EnumSet<Metadata.XContentContext> context() {
            return EnumSet.of(Metadata.XContentContext.GATEWAY);
        }
    }
}
