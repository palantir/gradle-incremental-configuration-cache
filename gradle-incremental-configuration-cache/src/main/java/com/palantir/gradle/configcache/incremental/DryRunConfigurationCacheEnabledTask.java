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

import com.google.common.base.Splitter;
import com.palantir.gradle.configcache.incremental.DryRunResult.Failure;
import com.palantir.gradle.configcache.incremental.DryRunResult.Success;
import com.palantir.gradle.utils.circleciartifacts.ArtifactLocation;
import com.palantir.gradle.utils.circleciartifacts.CircleCiArtifacts;
import com.palantir.gradle.utils.environmentvariables.EnvironmentVariables;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.TaskAction;

public abstract class DryRunConfigurationCacheEnabledTask extends AbstractDryRunTask {

    @Nested
    protected abstract EnvironmentVariables getEnvironmentVariables();

    @Nested
    protected abstract CircleCiArtifacts getCircleCiArtifacts();

    public DryRunConfigurationCacheEnabledTask() {
        getMarkerOutputFile()
                .set(getTemporaryDir()
                        .toPath()
                        .resolve("dryRunConfigurationCacheEnabled.marker")
                        .toFile());
    }

    @TaskAction
    public final void check() throws IOException {
        DryRunResult result =
                dryRun(List.of("--configuration-cache", "-Pconfiguration-cache-compatible-for-all-tasks"));

        if (result instanceof Success) {
            Files.writeString(getMarkerOutputFile().get().getAsFile().toPath(), "up-to-date");
            return;
        }

        String message = errorMessage(((Failure) result).errorOutput());
        throw new RuntimeException(message);
    }

    private String errorMessage(String output) {
        boolean isCircleCI = getEnvironmentVariables()
                .envVarOrFromTestingProperty("CIRCLECI")
                .isPresent();

        if (!isCircleCI) {
            return buildDetailedErrorMessage(output, Optional.empty());
        }

        return circleCiErrorMessage(output);
    }

    private String circleCiErrorMessage(String output) {
        try {
            String artifactPath = "configuration-cache-validation-report/validation-report.txt";
            ArtifactLocation artifactLocation =
                    getCircleCiArtifacts().resolveArtifactLocation(artifactPath).get();

            Path artifactLocationPath =
                    artifactLocation.physicalPath().getAsFile().toPath();
            Files.createDirectories(artifactLocationPath.getParent());
            Files.writeString(artifactLocationPath, output);

            return buildDetailedErrorMessage(output, Optional.of(artifactLocation.circleLink()));
        } catch (IOException e) {
            // Fall back to including output directly if we can't create the artifact
            return String.format(
                    """
                    %s

                    (Failed to save CircleCI artifact: %s)
                    """,
                    buildDetailedErrorMessage(output, Optional.empty()), e.getMessage());
        }
    }

    @SuppressWarnings("checkstyle:LineLength")
    private String buildDetailedErrorMessage(String outputContent, Optional<String> validationReportUrl) {
        Optional<String> configCacheReportPath = extractConfigCacheReportPath(outputContent);

        // Get the proper artifact URL for the configuration cache report
        Optional<String> configCacheReportUrl = Optional.empty();
        if (configCacheReportPath.isPresent() && validationReportUrl.isPresent()) {
            configCacheReportUrl = configCacheReportArtifactUrl(configCacheReportPath.get());
        }

        // Build the reports section based on what's available
        String reportsSection = reportsSection(validationReportUrl, configCacheReportUrl, configCacheReportPath);

        // Build the main message (shared between CI and local)
        String mainMessage = String.format(
                """
            ❌ CONFIGURATION CACHE ALLOW LIST VALIDATION FAILED

            WHAT HAPPENED:
              Some task / tasks in the allow list failed to run with configuration cache enabled.

            %s

            WHY THIS MATTERS:
              This validation task runs all the tasks marked as configuration cacheable in the allow list,
              to ensure the allow list contains only cacheable tasks.
              Regular CI builds may mask configuration cache issues when non-cacheable
              tasks disable the configuration cache.

            HOW TO FIX (only if you have introduced a task, or upgraded a plugin):

              1. Review the %s for specific issues

              2. Common fixes for configuration cache problems:
                  • Task.project at execution → Inject services (ProjectLayout, FileSystemOperations)
                  • External processes → Use ProviderFactory.exec() or GradleExec (https://github.com/palantir/gradle-utils?tab=readme-ov-file#gradleexec)
                  • Cannot serialize Gradle model types → Don't pass in full object use Property<T> for specific values or inject services

              3. If you upgraded a plugin, verify it supports configuration cache

              📚 Gradle Guide: https://github.com/palantir/gradle-guide/blob/develop/guide/adopting-the-configuration-cache.md
              📚 Gradle docs: https://docs.gradle.org/current/userguide/configuration_cache.html
            """,
                reportsSection,
                configCacheReportUrl.isPresent() || configCacheReportPath.isPresent()
                        ? "Gradle configuration cache report"
                        : "output above");

        // Add validation output for local development only
        if (validationReportUrl.isEmpty() && outputContent != null && !outputContent.isEmpty()) {
            mainMessage +=
                    """

                VALIDATION OUTPUT: to see full output above re-run with --info
                """;
            // the outputContent can be very large and annoying to scroll past so only show when running with info
            getLogger().info(outputContent);
        }

        return mainMessage;
    }

    private static String reportsSection(
            Optional<String> validationReportUrl,
            Optional<String> configCacheReportUrl,
            Optional<String> configCacheReportPath) {

        Optional<String> configCacheLocation =
                configCacheReportUrl.or(() -> configCacheReportPath.map(path -> "file://" + path));

        Optional<String> configCacheReport = configCacheLocation.map(location ->
                String.format("%s  📊 Config cache report: %s", validationReportUrl.isPresent() ? "\n" : "", location));

        return validationReportUrl
                .map(url -> String.format("  📋 Full output: %s%s", url, configCacheReport.orElse("")))
                .orElseGet(() -> configCacheReport.orElse("  To see full output above re-run with --info"));
    }

    private Optional<String> extractConfigCacheReportPath(String output) {
        if (output == null) {
            return Optional.empty();
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
            return Optional.of(output.substring(startIndex, endIndex).trim());
        }

        return Optional.empty();
    }

    private Optional<String> configCacheReportArtifactUrl(String localReportPath) {
        // Extract the relative path within configuration-cache directory
        // e.g., from /path/to/build/reports/configuration-cache/abc123/def456/configuration-cache-report.html
        // we want configuration-cache-reports/abc123/def456/configuration-cache-report.html
        List<String> pathParts = Splitter.on("/configuration-cache/").splitToList(localReportPath);
        if (pathParts.size() > 1) {
            String artifactPath = "configuration-cache-reports/" + pathParts.get(1);
            ArtifactLocation artifactLocation =
                    getCircleCiArtifacts().resolveArtifactLocation(artifactPath).get();
            return Optional.of(artifactLocation.circleLink());
        }
        return Optional.empty();
    }
}
