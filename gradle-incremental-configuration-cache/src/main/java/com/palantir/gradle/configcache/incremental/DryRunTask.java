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
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.tooling.GradleConnectionException;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;

public abstract class DryRunTask extends DefaultTask {

    private static final Pattern DRY_RUN_TASK_PATTERN = Pattern.compile("(:[\\w:-]+)\\s+SKIPPED");

    @Input
    public abstract SetProperty<String> getTasksToDryRun();

    @Input
    public abstract ListProperty<String> getArguments();

    @Input
    @org.gradle.api.tasks.Optional
    public abstract Property<String> getInitScript();

    @Internal
    protected abstract RegularFileProperty getResultFile();

    @Internal
    protected abstract RegularFileProperty getErrorFile();

    @Inject
    protected abstract ProjectLayout getProjectLayout();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    public DryRunTask() {
        getResultFile().convention(getOutputDirectory().file("dryRunResult"));
        getErrorFile().convention(getOutputDirectory().file("dryRunError"));
    }

    @TaskAction
    public final void dryRun() throws IOException {
        Files.createDirectories(getOutputDirectory().get().getAsFile().toPath());

        Set<String> tasks = getTasksToDryRun().get();
        if (tasks.isEmpty()) {
            getLogger().info("No tasks to dry-run");
            TaskListFile.write(getResultFile().get().getAsFile().toPath(), new TreeSet<>());
            writeError("");
            return;
        }

        getLogger().info("Dry-running {} tasks", tasks.size());

        GradleConnector connector = GradleConnector.newConnector()
                .forProjectDirectory(getProjectLayout().getProjectDirectory().getAsFile());

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream(16 * 1024);

        try (ProjectConnection connection = connector.connect()) {
            connection
                    .newBuild()
                    .withArguments(buildArguments())
                    .forTasks(tasks.toArray(new String[0]))
                    .setStandardOutput(outputStream)
                    .setStandardError(outputStream)
                    .run();

            getLogger().info("All {} tasks dry-ran successfully", tasks.size());

        } catch (GradleConnectionException e) {
            getLogger().info("Failed to run Dry-run tasks", e);
            TaskListFile.write(getResultFile().get().getAsFile().toPath(), new TreeSet<>());
            writeError(outputStream.toString(StandardCharsets.UTF_8));
            return;
        }

        TaskListFile.write(
                getResultFile().get().getAsFile().toPath(),
                parseDryRunResult(outputStream.toString(StandardCharsets.UTF_8)));
        writeError("");
    }

    private ImmutableList<String> buildArguments() {
        ImmutableList.Builder<String> argumentsBuilder = ImmutableList.builder();
        argumentsBuilder.add("--dry-run");
        argumentsBuilder.add("--console=plain");
        argumentsBuilder.addAll(getArguments().get());

        // GradleConnector runs builds in a separate process, so we must explicitly pass init scripts.
        // This is required for Nebula tests, which rely on init scripts for test setup.
        if (getInitScript().isPresent() && !getInitScript().get().isBlank()) {
            argumentsBuilder.add("--init-script=" + getInitScript().get());
        }

        return argumentsBuilder.build();
    }

    private Set<String> parseDryRunResult(String output) {
        return DRY_RUN_TASK_PATTERN
                .matcher(output)
                .results()
                .map(m -> m.group(1))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    public final Set<String> dryRunResult() {
        return new TaskListFile(getResultFile().getAsFile().get().toPath()).loadTasks();
    }

    public final Optional<String> dryRunError() throws IOException {
        Path path = getErrorFile().get().getAsFile().toPath();
        if (Files.notExists(path)) {
            return Optional.empty();
        }

        String content = Files.readString(path, StandardCharsets.UTF_8).trim();
        return content.isEmpty() ? Optional.empty() : Optional.of(content);
    }

    private void writeError(String content) throws IOException {
        Files.writeString(getErrorFile().get().getAsFile().toPath(), content, StandardCharsets.UTF_8);
    }
}
