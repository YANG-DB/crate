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

package io.crate.action.sql;

import javax.annotation.Nullable;

import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Provider;
import org.elasticsearch.common.inject.Singleton;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.transport.NodeDisconnectedException;

import io.crate.analyze.Analyzer;
import io.crate.execution.engine.collect.stats.JobsLogs;
import io.crate.metadata.NodeContext;
import io.crate.metadata.settings.CoordinatorSessionSettings;
import io.crate.planner.DependencyCarrier;
import io.crate.planner.Planner;
import io.crate.user.User;


@Singleton
public class SQLOperations {

    public static final Setting<Boolean> NODE_READ_ONLY_SETTING = Setting.boolSetting(
        "node.sql.read_only",
        false,
        Setting.Property.NodeScope);

    private final NodeContext nodeCtx;
    private final Analyzer analyzer;
    private final Planner planner;
    private final Provider<DependencyCarrier> executorProvider;
    private final JobsLogs jobsLogs;
    private final ClusterService clusterService;
    private final boolean isReadOnly;
    private volatile boolean disabled;

    @Inject
    public SQLOperations(NodeContext nodeCtx,
                         Analyzer analyzer,
                         Planner planner,
                         Provider<DependencyCarrier> executorProvider,
                         JobsLogs jobsLogs,
                         Settings settings,
                         ClusterService clusterService) {
        this.nodeCtx = nodeCtx;
        this.analyzer = analyzer;
        this.planner = planner;
        this.executorProvider = executorProvider;
        this.jobsLogs = jobsLogs;
        this.clusterService = clusterService;
        this.isReadOnly = NODE_READ_ONLY_SETTING.get(settings);
    }

    private Session createSession(CoordinatorSessionSettings sessionSettings) {
        if (disabled) {
            throw new NodeDisconnectedException(clusterService.localNode(), "sql");
        }
        return new Session(
            nodeCtx,
            analyzer,
            planner,
            jobsLogs,
            isReadOnly,
            executorProvider.get(),
            sessionSettings);
    }

    public Session newSystemSession() {
        return createSession(CoordinatorSessionSettings.systemDefaults());
    }

    public Session createSession(@Nullable String defaultSchema, User authenticatedUser) {
        CoordinatorSessionSettings sessionSettings;
        if (defaultSchema == null) {
            sessionSettings = new CoordinatorSessionSettings(authenticatedUser);
        } else {
            sessionSettings = new CoordinatorSessionSettings(authenticatedUser, defaultSchema);
        }

        return createSession(sessionSettings);
    }

    /**
     * Disable processing of new sql statements.
     * {@link io.crate.cluster.gracefulstop.DecommissioningService} must call this while before starting to decommission.
     */
    public void disable() {
        disabled = true;
    }

    /**
     * (Re-)Enable processing of new sql statements
     * {@link io.crate.cluster.gracefulstop.DecommissioningService} must call this when decommissioning is aborted.
     */
    public void enable() {
        disabled = false;
    }

    public boolean isEnabled() {
        return !disabled;
    }
}
