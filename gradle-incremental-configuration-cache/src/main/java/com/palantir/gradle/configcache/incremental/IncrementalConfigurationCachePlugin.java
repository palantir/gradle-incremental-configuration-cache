/*
 * (c) Copyright 2025 Palantir Technologies Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.gradle.configcache.incremental;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import javax.inject.Inject;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.flow.FlowScope;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.internal.cc.impl.problems.ConfigurationCacheProblems;

public abstract class IncrementalConfigurationCachePlugin implements Plugin<Project> {
    @Inject
    protected abstract FlowScope getFlowScope();

    @Inject
    protected abstract ConfigurationCacheProblems getConfigurationCacheProblems();

    @Inject
    protected abstract ProviderFactory getProviderFactory();

    public static final Path ALLOW_LIST_FILE = Path.of("gradle/configuration-cache-allowed-tasks");
    private static final String ALLOW_LIST_INFO = "What is the configuration cache allow list?: "
            + "https://github.com/palantir/gradle-incremental-configuration-cache/blob/develop/README.md#motivation";

    @Override
    public final void apply(Project project) {
        if (!project.getRootProject().equals(project)) {
            throw new RuntimeException("Must be applied only to root project");
        }

        Path allowListPath = project.getRootProject().getProjectDir().toPath().resolve(ALLOW_LIST_FILE);
        if (!Files.exists(allowListPath)) {
            throw new RuntimeException("Configuration cache allow list file not found at %s\n%s"
                    .formatted(allowListPath, ALLOW_LIST_INFO));
        }

        AllowListFile allowList = new AllowListFile(allowListPath);
        Set<String> enabledTasks = allowList.loadAllowedTasks();
        project.getAllprojects().forEach(proj -> proj.getTasks().configureEach(task -> {
            if (!enabledTasks.contains(task.getPath())) {
                task.notCompatibleWithConfigurationCache(
                        "Configuration cache is not enabled for this task, as it was not included in %s\n%s"
                                .formatted(allowListPath, ALLOW_LIST_INFO));
            }
        }));

        getFlowScope().always(ReassureUsers.class, spec -> spec.getParameters()
                .getProblems()
                .set(getProviderFactory().provider(() -> getConfigurationCacheProblems())));
    }
}
