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
import com.palantir.gradle.utils.environmentvariables.EnvironmentVariables;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskAction;
import org.gradle.tooling.GradleConnectionException;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;

public abstract class DryRunConfigurationCacheAllowListTasks extends DefaultTask {

    @Input
    public abstract SetProperty<String> getTasksToValidate();

    @Input
    @Optional
    public abstract Property<String> getInitScript();

    @Inject
    protected abstract ProjectLayout getProjectLayout();

    @Nested
    protected abstract EnvironmentVariables getEnvironmentVariables();

    @TaskAction
    public final void validate() throws IOException {
        Set<String> tasks = getTasksToValidate().get();

        if (tasks.isEmpty()) {
            getLogger().info("No tasks to validate");
            return;
        }

        getLogger().info("Validating that {} tasks run with the configuration cache", tasks.size());

        GradleConnector connector = GradleConnector.newConnector()
                .forProjectDirectory(getProjectLayout().getProjectDirectory().getAsFile());

        try (ProjectConnection connection = connector.connect()) {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            ImmutableList<String> arguments = buildArguments();

            try {
                connection
                        .newBuild()
                        .forTasks(tasks.toArray(new String[0]))
                        .withArguments(arguments)
                        .setStandardOutput(outputStream)
                        .setStandardError(outputStream)
                        .run();

                getLogger().info("All {} tasks passed configuration cache validation", tasks.size());

            } catch (GradleConnectionException e) {
                String message = errorMessage(outputStream);
                throw new RuntimeException(message);
            }
        }
    }

    private ImmutableList<String> buildArguments() {
        ImmutableList.Builder<String> argumentsBuilder = ImmutableList.builder();
        argumentsBuilder.add("--dry-run", "--configuration-cache");

        // GradleConnector runs builds in a separate process, so we must explicitly pass init scripts.
        // This is required for Nebula tests, which rely on init scripts for test setup.
        if (getInitScript().isPresent() && !getInitScript().get().isBlank()) {
            argumentsBuilder.add("--init-script=" + getInitScript().get());
        }

        return argumentsBuilder.build();
    }

    private String errorMessage(ByteArrayOutputStream outputStream) {
        boolean isCircleCI = getEnvironmentVariables()
                .envVarOrFromTestingProperty("CIRCLECI")
                .isPresent();

        if (!isCircleCI) {
            return "Configuration cache validation failed. See error output for details.\n" + outputStream;
        }

        return circleCiErrorMessage(outputStream);
    }

    private String circleCiErrorMessage(ByteArrayOutputStream outputStream) {
        try {
            String artifactsDir = getEnvironmentVariables()
                    .envVarOrFromTestingProperty("CIRCLE_ARTIFACTS")
                    .orElse("/home/circleci/artifacts")
                    .get();

            Path reportDir = Path.of(artifactsDir, "configuration-cache-validation-report");
            Files.createDirectories(reportDir);

            Path reportFile = reportDir.resolve("validation-report");
            Files.write(reportFile, outputStream.toByteArray());

            return String.format("Configuration cache validation failed. See report at %s for details.", reportFile);
        } catch (IOException e) {
            return String.format(
                    """
                    Configuration cache validation failed. See error output for details.
                    %s
                    (Failed to save CircleCI artifact: %s )
                    """,
                    outputStream, e.getMessage());
        }
    }
}
