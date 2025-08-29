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
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.tooling.GradleConnectionException;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;

public abstract class DryRunTasksTask extends DefaultTask {

    @Input
    public abstract Property<AllowListFile> getAllowList();

    @Input
    @org.gradle.api.tasks.Optional
    public abstract Property<String> getInitScript();

    @Inject
    protected abstract ProjectLayout getProjectLayout();

    protected final Set<String> runTasksInDryRunMode(Set<String> allowListTasks, ImmutableList<String> arguments) {
        if (allowListTasks.isEmpty()) {
            getLogger().info("No tasks to run");
            return new TreeSet<>();
        }

        getLogger().info("Running {} tasks in dry-run mode", allowListTasks.size());

        GradleConnector connector = GradleConnector.newConnector()
                .forProjectDirectory(getProjectLayout().getProjectDirectory().getAsFile());

        try (ProjectConnection connection = connector.connect()) {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

            try {
                connection
                        .newBuild()
                        .forTasks(allowListTasks.toArray(new String[0]))
                        .withArguments(arguments)
                        .setStandardOutput(outputStream)
                        .setStandardError(outputStream)
                        .run();

                getLogger().info("All {} tasks completed dry-run successfully", allowListTasks.size());

                String output = outputStream.toString(StandardCharsets.UTF_8);

                return Pattern.compile("(:[\\w:-]+)\\s+SKIPPED")
                        .matcher(output)
                        .results()
                        .map(m -> m.group(1))
                        .collect(Collectors.toCollection(TreeSet::new));

            } catch (GradleConnectionException e) {
                String output = outputStream.toString(StandardCharsets.UTF_8);
                throw new DryRunException("Task execution failed", output, e);
            }
        }
    }

    protected final ImmutableList<String> buildBaseArguments() {
        ImmutableList.Builder<String> argumentsBuilder = ImmutableList.builder();
        argumentsBuilder.add("--dry-run");

        // GradleConnector runs builds in a separate process, so we must explicitly pass init scripts.
        // This is required for Nebula tests, which rely on init scripts for test setup.
        if (getInitScript().isPresent() && !getInitScript().get().isBlank()) {
            argumentsBuilder.add("--init-script=" + getInitScript().get());
        }

        return argumentsBuilder.build();
    }

    protected static final class DryRunException extends RuntimeException {
        private final String output;

        public DryRunException(String message, String output, Throwable cause) {
            super(message, cause);
            this.output = output;
        }

        public String getOutput() {
            return output;
        }
    }
}
