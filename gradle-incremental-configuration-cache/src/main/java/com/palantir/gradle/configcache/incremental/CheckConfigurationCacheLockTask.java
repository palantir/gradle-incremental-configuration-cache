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

import com.palantir.gradle.failurereports.exceptions.ExceptionWithSuggestion;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;

public abstract class CheckConfigurationCacheLockTask extends DryRunTask {

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getLockFile();

    @Input
    @Option(
            option = "fix",
            description = "Whether to apply the suggested fix to configuration-cache-allowed-tasks.lock")
    public abstract Property<Boolean> getShouldFix();

    public CheckConfigurationCacheLockTask() {
        getArguments().set(List.of("--quiet", "--no-configuration-cache"));
        getShouldFix().set(false);
        getDryRunResult()
                .set(getTemporaryDir()
                        .toPath()
                        .resolve("dryRunNoConfigurationCache")
                        .toFile());
    }

    @TaskAction
    public final void check() {
        Path lockPath = getLockFile().getSingleFile().toPath();
        if (Files.notExists(lockPath) && !getShouldFix().get()) {
            throw new ExceptionWithSuggestion(
                    """
                Lock file does not exist at %s.
                Run `./gradlew :checkConfigurationCacheLock --fix` to create the lock file.
                """
                            .formatted(lockPath),
                    "./gradlew :checkConfigurationCacheLock --fix");
        }

        Set<String> dryRanTasks = dryRunResult();

        if (getShouldFix().get()) {
            TaskListFile.write(lockPath, dryRanTasks);
            getLogger().lifecycle("Lock file updated with {} tasks", dryRanTasks.size());
            return;
        }

        Set<String> lockFileTasks = new TaskListFile(lockPath).loadTasks();

        if (lockFileTasks.equals(dryRanTasks)) {
            getLogger().lifecycle("Lock file is up to date with {} tasks", lockFileTasks.size());
            return;
        }

        Set<String> inLockNotInDryRun = new HashSet<>(lockFileTasks);
        inLockNotInDryRun.removeAll(dryRanTasks);

        Set<String> inDryRunNotInLock = new HashSet<>(dryRanTasks);
        inDryRunNotInLock.removeAll(lockFileTasks);

        String diffMessage = buildLockFileDiffMessage(lockFileTasks, dryRanTasks, inLockNotInDryRun, inDryRunNotInLock);
        throw new RuntimeException(diffMessage);
    }

    private String buildLockFileDiffMessage(
            Set<String> lockFileTasks,
            Set<String> dryRanTasks,
            Set<String> inLockNotInDryRun,
            Set<String> inDryRunNotInLock) {

        StringBuilder diffMessage = new StringBuilder();
        diffMessage.append(
                """
                Lock file does not match the tasks that would run.
                Run `./gradlew :checkConfigurationCacheLock --fix` to update the lock file.
                """);

        if (!inLockNotInDryRun.isEmpty()) {
            diffMessage.append("Tasks in lock file but NOT executed (may have been removed or renamed):\n");
            inLockNotInDryRun.stream()
                    .sorted()
                    .forEach(task -> diffMessage.append("  - ").append(task).append("\n"));
            diffMessage.append("\n");
        }

        if (!inDryRunNotInLock.isEmpty()) {
            diffMessage.append("Tasks that would be executed but NOT in lock file:\n");
            inDryRunNotInLock.stream()
                    .sorted()
                    .forEach(task -> diffMessage.append("  + ").append(task).append("\n"));
            diffMessage.append("\n");
        }

        diffMessage
                .append("Total tasks in lock file: ")
                .append(lockFileTasks.size())
                .append("\n");
        diffMessage.append("Total tasks that would execute: ").append(dryRanTasks.size());

        return diffMessage.toString();
    }
}
