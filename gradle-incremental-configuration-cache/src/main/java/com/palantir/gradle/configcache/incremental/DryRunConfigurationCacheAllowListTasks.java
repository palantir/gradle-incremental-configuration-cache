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
import com.palantir.gradle.utils.circleciartifacts.ArtifactLocation;
import com.palantir.gradle.utils.circleciartifacts.CircleCiArtifacts;
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

    @Nested
    protected abstract CircleCiArtifacts getCircleCiArtifacts();

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
            return buildDetailedErrorMessage(outputStream.toString(), null);
        }

        return circleCiErrorMessage(outputStream);
    }

    private String circleCiErrorMessage(ByteArrayOutputStream outputStream) {
        try {
            String artifactPath = "configuration-cache-validation-report/validation-report.txt";
            ArtifactLocation artifactLocation =
                    getCircleCiArtifacts().resolveArtifactLocation(artifactPath).get();

            Path reportFile = artifactLocation.physicalPath().getAsFile().toPath();
            Files.createDirectories(reportFile.getParent());
            Files.write(reportFile, outputStream.toByteArray());

            return buildDetailedErrorMessage(outputStream.toString(), artifactLocation.externalLocation());
        } catch (IOException e) {
            // Fall back to including output directly if we can't create the artifact
            return String.format(
                    """
                    %s

                    (Failed to save CircleCI artifact: %s)
                    """,
                    buildDetailedErrorMessage(outputStream.toString(), null), e.getMessage());
        }
    }

    private String buildDetailedErrorMessage(String outputContent, String validationReportUrl) {
        String configCacheReportPath = extractConfigCacheReportPath(outputContent);

        // Get the proper artifact URL for the configuration cache report
        String configCacheReportUrl = null;
        if (configCacheReportPath != null && validationReportUrl != null) {
            configCacheReportUrl = getConfigCacheReportArtifactUrl(configCacheReportPath);
        }

        // Build the reports section based on what's available
        String reportsSection = getString(validationReportUrl, configCacheReportUrl, configCacheReportPath);

        // Build the main message (shared between CI and local)
        String mainMessage = String.format(
                """
            ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            ❌ CONFIGURATION CACHE VALIDATION FAILED
            ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

            WHAT HAPPENED:
              Failed to dry run tasks with configuration cache enabled.

            %s

            WHY THIS MATTERS:
              These tasks are validated together with configuration cache fully
              enabled to detect issues that only occur when all cacheable tasks
              run. Regular CI builds may mask these issues when non-cacheable
              tasks disable the configuration cache.

            HOW TO FIX:

              1. Review the%s for specific issues

              2. Common fixes for configuration cache problems:
                 • Project object in task → Use Provider/Property APIs
                 • Non-serializable inputs → Mark @Internal or make serializable
                 • System properties → Use providers.systemProperty()
                 • Environment variables → Use providers.environmentVariable()
                 • File operations at config → Wrap in providers or defer

              3. If you upgraded a plugin, verify it supports configuration cache

              📚 Gradle docs: https://docs.gradle.org/current/userguide/configuration_cache.html
            ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            """,
                reportsSection,
                configCacheReportUrl != null || configCacheReportPath != null
                        ? " Gradle configuration cache report"
                        : " output above");

        // Add validation output for local development only
        if (validationReportUrl == null && outputContent != null && !outputContent.isEmpty()) {
            mainMessage += String.format(
                    """

                VALIDATION OUTPUT:
                ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
                %s
                """,
                    outputContent);
        }

        return mainMessage;
    }

    private static String getString(
            String validationReportUrl, String configCacheReportUrl, String configCacheReportPath) {
        String reportsSection;
        if (validationReportUrl != null) {
            reportsSection = String.format(
                    "  📋 Full output: %s%s",
                    validationReportUrl,
                    configCacheReportUrl != null ? "\n  📊 Config cache report: " + configCacheReportUrl : "");
        } else if (configCacheReportPath != null) {
            reportsSection = "  📊 Config cache report: file://" + configCacheReportPath;
        } else {
            reportsSection = "  See validation output below";
        }
        return reportsSection;
    }

    private String extractConfigCacheReportPath(String output) {
        if (output == null) {
            return null;
        }

        // Look for "See the complete report at file:///path/to/report.html"
        String pattern = "See the complete report at file://";
        int index = output.indexOf(pattern);

        if (index != -1) {
            int startIndex = index + pattern.length();
            int endIndex = output.indexOf('\n', startIndex);
            if (endIndex == -1) {
                endIndex = output.length();
            }
            return output.substring(startIndex, endIndex).trim();
        }

        return null;
    }

    private String getConfigCacheReportArtifactUrl(String localReportPath) {
        // Extract the relative path within configuration-cache directory
        // e.g., from /path/to/build/reports/configuration-cache/abc123/def456/configuration-cache-report.html
        // we want configuration-cache-reports/abc123/def456/configuration-cache-report.html
        String[] pathParts = localReportPath.split("/configuration-cache/");
        if (pathParts.length > 1) {
            String artifactPath = "configuration-cache-reports/" + pathParts[1];
            ArtifactLocation artifactLocation =
                    getCircleCiArtifacts().resolveArtifactLocation(artifactPath).get();
            return artifactLocation.externalLocation();
        }
        return null;
    }
}
