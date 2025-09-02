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

import java.io.IOException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import org.gradle.api.DefaultTask;
import org.gradle.api.UncheckedIOException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;

public abstract class CheckConfigurationCacheAllowListTask extends DefaultTask {

    @InputFile
    public abstract RegularFileProperty getDryRunTasksFile();

    @Input
    public abstract Property<AllowListFile> getAllowListLock();

    @Input
    @Option(
            option = "fix",
            description = "Whether to apply the suggested fix to configuration-cache-allowed-tasks.lock")
    public abstract Property<Boolean> getShouldFix();

    @TaskAction
    public final void check() {
        // Check if lock file exists
        if (!Files.exists(getAllowListLock().get().path())) {
            if (getShouldFix().get()) {
                getLogger()
                        .info(
                                "Creating missing lock file at {}",
                                getAllowListLock().get().path());
                try {
                    Files.createFile(getAllowListLock().get().path());
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to create lock file", e);
                }
            } else {
                throw new RuntimeException(
                        """
                    Lock file does not exist at %s.
                    Run `./gradlew :checkConfigurationCacheAllowListLock --fix` to create the lock file.
                    """
                                .formatted(getAllowListLock().get().path()));
            }
        }

        Set<String> dryRanTasks =
                new AllowListFile(getDryRunTasksFile().getAsFile().get().toPath()).loadAllowedTasks();
        Set<String> lockFileTasks = getAllowListLock().get().loadAllowedTasks();

        if (lockFileTasks.equals(dryRanTasks)) {
            getLogger().lifecycle("Lock file is up to date with {} tasks", lockFileTasks.size());
            return;
        }

        // Find differences
        Set<String> inLockNotInDryRun = new HashSet<>(lockFileTasks);
        inLockNotInDryRun.removeAll(dryRanTasks);

        Set<String> inDryRunNotInLock = new HashSet<>(dryRanTasks);
        inDryRunNotInLock.removeAll(lockFileTasks);

        if (getShouldFix().get()) {
            AllowListFile.write(getAllowListLock().get().path(), dryRanTasks);
        } else {
            String diffMessage =
                    buildLockFileDiffMessage(lockFileTasks, dryRanTasks, inLockNotInDryRun, inDryRunNotInLock);
            throw new RuntimeException(diffMessage);
        }
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
                Run `./gradlew :checkConfigurationCacheAllowListLock --fix` to update the lock file.
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
