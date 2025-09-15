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

import com.palantir.gradle.utils.environmentvariables.EnvironmentVariables;
import java.nio.file.Path;
import java.util.Locale;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.testing.Test;
import org.gradle.language.base.plugins.LifecycleBasePlugin;

public abstract class ValidateConfigurationCachePlugin implements Plugin<Project> {

    @Nested
    protected abstract EnvironmentVariables getEnvironmentVariables();

    @Override
    public final void apply(Project project) {

        Path targetTasksPath = project.getRootProject()
                .getProjectDir()
                .toPath()
                .resolve(IncrementalConfigurationCachePlugin.TARGET_TASKS_FILE);

        TaskProvider<ValidateConfigurationCacheEnabledTask> validationTask = project.getTasks()
                .register(
                        ValidateConfigurationCacheEnabledTask.VALIDATION_TASK_NAME,
                        ValidateConfigurationCacheEnabledTask.class,
                        task -> {
                            task.getTasksToRunFile().set(targetTasksPath.toFile());
                            task.onlyIf(
                                    "Running on CircleCI node 0 only",
                                    _t -> getEnvironmentVariables()
                                                    .envVarOrFromTestingProperty("CIRCLECI")
                                                    .isPresent()
                                            && getEnvironmentVariables()
                                                    .isCircleNode0OrLocal()
                                                    .get());
                        });

        project.getPluginManager().apply(LifecycleBasePlugin.class);
        project.getTasks().named("check").configure(task -> task.dependsOn(validationTask));

        if (project.hasProperty("prevent-dangerous-task-operations")) {
            // This allows for fast validation of configuration cache compatibility across the project without
            // actually running tests, publishing, or pushing.
            project.getAllprojects().forEach(proj -> proj.getTasks().configureEach(task -> {
                if (task instanceof Test testTask) {
                    testTask.getDryRun().set(true);
                }
                if (task instanceof PublishToMavenRepository) {
                    task.setEnabled(false);
                }
                if (task.getName().toLowerCase(Locale.ROOT).contains("docker")
                        && task.getName().toLowerCase(Locale.ROOT).contains("push")) {
                    task.setEnabled(false);
                }
            }));
        }
    }
}
