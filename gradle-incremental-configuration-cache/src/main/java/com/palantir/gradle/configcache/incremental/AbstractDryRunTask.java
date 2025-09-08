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

import com.google.common.collect.ImmutableList;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;

public abstract class AbstractDryRunTask extends DefaultTask {
    private static final Pattern DRY_RUN_TASK_PATTERN = Pattern.compile("(:[\\w:-]+)\\s+SKIPPED");

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getDryRunTasksFile();

    @Input
    @org.gradle.api.tasks.Optional
    public abstract Property<String> getInitScript();

    @Inject
    protected abstract ProjectLayout getProjectLayout();

    @OutputFile
    public abstract RegularFileProperty getMarkerOutputFile();

    public AbstractDryRunTask() {
        getMarkerOutputFile()
                .set(getTemporaryDir().toPath().resolve(getName() + ".marker").toFile());
    }

    protected final DryRunResult dryRun(List<String> extraArgs) throws IOException {
        Set<String> tasksToDryRun =
                new TaskListFile(getDryRunTasksFile().getAsFile().get().toPath()).loadTasks();

        if (tasksToDryRun.isEmpty()) {
            getLogger().info("No tasks to dry-run");
            return DryRunResult.success(new TreeSet<>());
        }

        getLogger().info("Dry-running {} tasks", tasksToDryRun.size());

        GradleConnector connector = GradleConnector.newConnector()
                .forProjectDirectory(getProjectLayout().getProjectDirectory().getAsFile());

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream(16 * 1024);

        try (ProjectConnection connection = connector.connect()) {
            connection
                    .newBuild()
                    .withArguments(buildArguments(extraArgs))
                    .forTasks(tasksToDryRun.toArray(new String[0]))
                    .setStandardOutput(outputStream)
                    .setStandardError(outputStream)
                    .run();

            getLogger().info("All {} tasks dry-ran successfully", tasksToDryRun.size());

        } catch (Exception e) {
            getLogger().info("Failed to run Dry-run tasks", e);
            String error = outputStream.toString(StandardCharsets.UTF_8);
            return DryRunResult.failure(error);
        }

        Files.writeString(
                getMarkerOutputFile().get().getAsFile().toPath(), outputStream.toString(StandardCharsets.UTF_8));
        Set<String> dryRunTasks = parseDryRunResult(outputStream.toString(StandardCharsets.UTF_8));
        return DryRunResult.success(dryRunTasks);
    }

    private ImmutableList<String> buildArguments(List<String> arguments) {
        return ImmutableList.<String>builder()
                .add("--dry-run")
                .add("--console=plain")
                .addAll(arguments)
                .addAll(
                        getInitScript().isPresent() && !getInitScript().get().isBlank()
                                ? ImmutableList.of(
                                        "--init-script=" + getInitScript().get())
                                : ImmutableList.of())
                .build();
    }

    private Set<String> parseDryRunResult(String output) {
        return DRY_RUN_TASK_PATTERN
                .matcher(output)
                .results()
                .map(match -> match.group(1))
                .collect(Collectors.toCollection(TreeSet::new));
    }
}
