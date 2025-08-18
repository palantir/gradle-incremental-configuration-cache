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

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import javax.inject.Inject;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.flow.BuildWorkResult;
import org.gradle.api.flow.FlowAction;
import org.gradle.api.flow.FlowParameters;
import org.gradle.api.flow.FlowProviders;
import org.gradle.api.flow.FlowScope;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.internal.cc.impl.ConfigurationCacheKey;
import org.gradle.util.GradleVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class IncrementalConfigurationCachePlugin implements Plugin<Project> {
    private static final Logger log = LoggerFactory.getLogger(IncrementalConfigurationCachePlugin.class);

    private static final Path ALLOW_LIST_FILE = Path.of("gradle/configuration-cache-allowed-tasks");
    private static final GradleVersion MIN_GRADLE_VERSION = GradleVersion.version("8.12.0");

    @Inject
    protected abstract FlowScope getFlowScope();

    @Inject
    protected abstract FlowProviders getFlowProviders();

    @Inject
    protected abstract ConfigurationCacheKey getConfigurationCacheKey();

    @Inject
    protected abstract ProjectLayout getProjectLayout();

    @Override
    public final void apply(Project project) {
        if (!project.getRootProject().equals(project)) {
            throw new RuntimeException("Must be applied only to root project");
        }

        // To prevent e.g. Gradle 7 repos from picking this up
        if (GradleVersion.current().compareTo(MIN_GRADLE_VERSION) < 0) {
            throw new IllegalStateException(
                    "Cannot apply IncrementalConfigurationCachePlugin with Gradle version older than %s"
                            .formatted(MIN_GRADLE_VERSION));
        }

        Path allowListPath = project.getRootProject().getProjectDir().toPath().resolve(ALLOW_LIST_FILE);
        if (!Files.exists(allowListPath)) {
            throw new RuntimeException(
                    "Configuration cache allowed tasks file not found at %s".formatted(allowListPath));
        }

        AllowListFile allowList = new AllowListFile(allowListPath);
        Set<String> enabledTasks = allowList.loadAllowedTasks();

        project.getAllprojects().forEach(proj -> proj.getTasks().configureEach(task -> {
            if (!enabledTasks.contains(task.getPath())) {
                task.notCompatibleWithConfigurationCache(
                        "Configuration cache is not enabled for this task, as it was not included in %s"
                                .formatted(ALLOW_LIST_FILE));
            }
        }));

        getFlowScope().always(CopyConfigurationCacheProblems.class, spec -> {
            spec.parameters(parameters -> {
                parameters.getBuildWorkResult().set(getFlowProviders().getBuildWorkResult());
                parameters
                        .getConfigurationCacheProblemsFile()
                        .set(getProjectLayout().getBuildDirectory().map(FileSystemLocation::getAsFile));
                parameters
                        .getOutputFile()
                        .set(getProjectLayout().getBuildDirectory().map(FileSystemLocation::getAsFile));
            });
        });
    }

    static final class CopyConfigurationCacheProblems implements FlowAction<CopyConfigurationCacheProblems.Parameters> {
        interface Parameters extends FlowParameters {
            @Input
            Property<BuildWorkResult> getBuildWorkResult();

            @Input
            Property<File> getConfigurationCacheProblemsFile();

            @Input
            Property<File> getOutputFile();
        }

        @Override
        public void execute(Parameters parameters) throws Exception {
            Files.copy(
                    parameters.getConfigurationCacheProblemsFile().get().toPath(),
                    parameters.getOutputFile().get().toPath());
        }
    }
}
