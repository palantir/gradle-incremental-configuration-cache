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
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import javax.inject.Inject;
import org.apache.commons.io.FileUtils;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.language.base.plugins.LifecycleBasePlugin;
import org.gradle.util.GradleVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class IncrementalConfigurationCachePlugin implements Plugin<Project> {
    private static final Logger log = LoggerFactory.getLogger(IncrementalConfigurationCachePlugin.class);

    private static final Path TARGET_TASKS_FILE = Path.of("gradle/configuration-cache-allowed-tasks");
    private static final Path LOCK_FILE = Path.of("gradle/configuration-cache-allowed-tasks.lock");
    private static final GradleVersion MIN_GRADLE_VERSION = GradleVersion.version("8.12.0");

    @Inject
    protected abstract ProjectLayout getProjectLayout();

    @Nested
    protected abstract EnvironmentVariables getEnvironmentVariables();

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

        Path targetTasksPath = project.getRootProject().getProjectDir().toPath().resolve(TARGET_TASKS_FILE);
        Path lockFilePath = project.getRootProject().getProjectDir().toPath().resolve(LOCK_FILE);
        if (!Files.exists(targetTasksPath)) {
            throw new RuntimeException(
                    "Configuration cache allowed tasks file not found at %s".formatted(targetTasksPath));
        }

        TaskListFile lockList = new TaskListFile(lockFilePath);
        Set<String> enabledTasks = lockList.loadTasks();

        Provider<Boolean> withoutConfigurationCache = project.getProviders()
                .gradleProperty("DISABLE_CONFIGURATION_CACHE")
                .map(Boolean::parseBoolean)
                .orElse(false);

        project.getAllprojects().forEach(proj -> proj.getTasks().configureEach(task -> {
            if (!enabledTasks.contains(task.getPath()) || withoutConfigurationCache.get()) {
                task.notCompatibleWithConfigurationCache(
                        "Configuration cache is not enabled for this task, as it was not included in %s"
                                .formatted(TARGET_TASKS_FILE));
            }
        }));

        TaskProvider<CheckConfigurationCacheLockTask> checkLock = project.getTasks()
                .register("checkConfigurationCacheLock", CheckConfigurationCacheLockTask.class, task -> {
                    task.getLock().set(lockList);
                    task.getShouldFix().set(false);
                    task.getTasks().set(new TaskListFile(targetTasksPath).loadTasks());
                });

        project.getPluginManager().apply(LifecycleBasePlugin.class);
        project.getTasks().named("check").configure(task -> task.dependsOn(checkLock));

        ensureReportsDirIsSymlinkedToCircleArtifacts();
    }

    private void ensureReportsDirIsSymlinkedToCircleArtifacts() {
        if (!getEnvironmentVariables().envVarOrFromTestingProperty("CIRCLECI").isPresent()) {
            return;
        }

        Path originalConfigurationCacheReportsDir = getProjectLayout()
                .getBuildDirectory()
                .dir("reports/configuration-cache")
                .get()
                .getAsFile()
                .toPath();

        Path circleArtifactsConfigurationCacheReportsDir = Path.of(
                getEnvironmentVariables()
                        .envVarOrFromTestingProperty("CIRCLE_ARTIFACTS")
                        // Some templates still do not set CIRCLE_ARTIFACTS :(
                        .orElse("/home/circleci/artifacts")
                        .get(),
                "configuration-cache-reports");

        // If the symlink is already correct, we're done.
        if (Files.isSymbolicLink(originalConfigurationCacheReportsDir)) {
            try {
                if (Files.readSymbolicLink(originalConfigurationCacheReportsDir)
                        .equals(circleArtifactsConfigurationCacheReportsDir)) {
                    return;
                }
            } catch (IOException e) {
                log.debug(
                        "Could not read existing symlink at '{}', will try to delete and recreate it",
                        originalConfigurationCacheReportsDir,
                        e);
            }
        }

        // If we're here, the path is either not a symlink, a broken/wrong symlink, or doesn't exist.
        // We need to remove it before creating our own symlink.
        FileUtils.deleteQuietly(originalConfigurationCacheReportsDir.toFile());

        createDirectories(originalConfigurationCacheReportsDir.getParent());
        createDirectories(circleArtifactsConfigurationCacheReportsDir);

        try {
            Files.createSymbolicLink(originalConfigurationCacheReportsDir, circleArtifactsConfigurationCacheReportsDir);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Could not symlink configuration cache reports dir ('%s') to circle artifacts ('%s')"
                            .formatted(
                                    originalConfigurationCacheReportsDir, circleArtifactsConfigurationCacheReportsDir),
                    e);
        }
    }

    private static void createDirectories(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create directories to '%s'".formatted(directory), e);
        }
    }
}
