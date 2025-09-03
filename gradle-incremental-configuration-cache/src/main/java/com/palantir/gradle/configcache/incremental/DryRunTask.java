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
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;

public abstract class DryRunTask extends DefaultTask {

    @Input
    public abstract SetProperty<String> getTasksToDryRun();

    @Input
    public abstract ListProperty<String> getArguments();

    @Input
    @org.gradle.api.tasks.Optional
    public abstract Property<String> getInitScript();

    @Inject
    protected abstract ProjectLayout getProjectLayout();

    @OutputFile
    public abstract RegularFileProperty getDryRunResult();

    @TaskAction
    public void dryRun() {
        Set<String> tasks = getTasksToDryRun().get();

        if (tasks.isEmpty()) {
            getLogger().info("No tasks to dry-run");
            TaskListFile.write(getDryRunResult().getAsFile().get().toPath(), new TreeSet<>());
        }

        getLogger().info("Dry-running {} tasks", tasks.size());

        GradleConnector connector = GradleConnector.newConnector()
                .forProjectDirectory(getProjectLayout().getProjectDirectory().getAsFile());

        try (ProjectConnection connection = connector.connect()) {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

            try {
                connection
                        .newBuild()
                        .withArguments(buildArguments())
                        .forTasks(tasks.toArray(new String[0]))
                        .setStandardOutput(outputStream)
                        .setStandardError(outputStream)
                        .run();

                getLogger().info("All {} tasks passed dry-ran successfully", tasks.size());

                TaskListFile.write(
                        getDryRunResult().getAsFile().get().toPath(),
                        Pattern.compile("(:[\\w:-]+)\\s+SKIPPED")
                                .matcher(outputStream.toString(StandardCharsets.UTF_8))
                                .results()
                                .map(m -> m.group(1))
                                .collect(Collectors.toCollection(TreeSet::new)));
            } catch (Exception e) {
                throw new DryRunException("Failed to dry run tasks: " + String.join(",", tasks), e, outputStream);
            }
        }
    }

    private ImmutableList<String> buildArguments() {
        ImmutableList.Builder<String> argumentsBuilder = ImmutableList.builder();
        argumentsBuilder.add("--dry-run");
        argumentsBuilder.addAll(getArguments().get());

        // GradleConnector runs builds in a separate process, so we must explicitly pass init scripts.
        // This is required for Nebula tests, which rely on init scripts for test setup.
        if (getInitScript().isPresent() && !getInitScript().get().isBlank()) {
            argumentsBuilder.add("--init-script=" + getInitScript().get());
        }

        return argumentsBuilder.build();
    }

    public final Set<String> dryRunResult() {
        return new TaskListFile(getDryRunResult().getAsFile().get().toPath()).loadTasks();
    }

    public static class DryRunException extends RuntimeException {

        private final ByteArrayOutputStream output;

        public DryRunException(String message, Throwable cause, ByteArrayOutputStream outputStream) {
            super(message, cause);
            this.output = outputStream;
        }

        public final ByteArrayOutputStream output() {
            return output;
        }
    }
}
