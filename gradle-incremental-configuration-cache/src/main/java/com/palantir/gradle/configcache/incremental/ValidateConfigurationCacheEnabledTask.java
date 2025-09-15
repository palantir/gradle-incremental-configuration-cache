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
import com.google.common.base.Throwables;
import com.palantir.gradle.configcache.incremental.RunResult.Failure;
import com.palantir.gradle.utils.circleciartifacts.ArtifactLocation;
import com.palantir.gradle.utils.circleciartifacts.CircleCiArtifacts;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.TaskAction;

public abstract class ValidateConfigurationCacheEnabledTask extends AbstractRunTask {

    @Nested
    protected abstract CircleCiArtifacts getCircleCiArtifacts();

    public ValidateConfigurationCacheEnabledTask() {
        getUseClonedDirectory().set(true);
    }

    @TaskAction
    public final void check() throws IOException {
        RunResult run = run(List.of(
                "--configuration-cache",
                "--continue",
                "-Pconfiguration-cache-compatible-for-all-tasks",
                "-Pprevent-dangerous-task-operations",
                "-PerrorProneDisable",
                // prevent recursion
                "-x",
                "validateConfigurationCacheEnabledTasks"));

        if (run instanceof Failure failure) {
            throw new RuntimeException(errorMessage(failure.output()));
        }
    }

    private String errorMessage(String output) {
        try {
            String artifactPath = "configuration-cache-validation-report/validation-report.txt";
            ArtifactLocation artifactLocation =
                    getCircleCiArtifacts().resolveArtifactLocation(artifactPath).get();

            Path artifactLocationPath =
                    artifactLocation.physicalPath().getAsFile().toPath();
            Files.createDirectories(artifactLocationPath.getParent());
            Files.writeString(artifactLocationPath, output);

            return buildDetailedErrorMessage(output, artifactLocation.circleLink());
        } catch (IOException e) {
            // Fall back to including output directly if we can't create the artifact
            return String.format(
                    """
                    %s

                    %s

                    (Failed to save CircleCI artifact: %s)
                    """,
                    buildDetailedErrorMessage(
                            output, "Failed to save validation-report.txt to CircleCi see error below"),
                    output,
                    Throwables.getStackTraceAsString(e));
        }
    }

    @SuppressWarnings("checkstyle:LineLength")
    private String buildDetailedErrorMessage(String outputContent, String validationReportUrl) {
        return String.format(
                """
            ❌ CONFIGURATION CACHE ALLOW LIST VALIDATION FAILED

            WHAT HAPPENED:
              Some task / tasks in the allow list failed to run with configuration cache enabled.

              📋 Full output: %s
              📊 Config cache report: %s

            WHY THIS MATTERS:
              This validation task runs all the tasks marked as configuration cacheable in the allow list,
              to ensure the allow list contains only configuration cache compatible tasks.
              Regular CI builds may mask configuration cache issues when configuration cache incompatible tasks
              disable the configuration cache.

            HOW TO FIX (only if you have introduced a task, or upgraded a plugin):

              1. Review the Gradle configuration cache report for specific issues

              2. Common fixes for configuration cache problems:
                  • Task.project at execution → Inject services (ProjectLayout, FileSystemOperations)
                  • External processes → Use ProviderFactory.exec() or GradleExec (https://github.com/palantir/gradle-utils?tab=readme-ov-file#gradleexec)
                  • Cannot serialize Gradle model types → Don't pass in full object use Property<T> for specific values or inject services

              3. If you upgraded a plugin, verify it supports configuration cache:
                  📚 Gradle Guide: https://github.com/palantir/gradle-guide/blob/develop/guide/adopting-the-configuration-cache.md
                  📚 Gradle docs: https://docs.gradle.org/current/userguide/configuration_cache.html
            """,
                validationReportUrl,
                extractConfigCacheReportPath(outputContent)
                        .flatMap(this::configCacheReportArtifactUrl)
                        .orElse("Failed to extract report location"));
    }

    private Optional<String> extractConfigCacheReportPath(String output) {
        // Look for "See the complete report at file:///path/to/report.html"
        String pattern = "See the complete report at file://";
        int index = output.indexOf(pattern);

        if (index == -1) {
            return Optional.empty();
        }

        int startIndex = index + pattern.length();
        int endIndex = output.indexOf('\n', startIndex);

        if (endIndex == -1) {
            endIndex = output.length();
        }
        return Optional.of(output.substring(startIndex, endIndex).trim());
    }

    private Optional<String> configCacheReportArtifactUrl(String localReportPath) {
        // Extract the relative path within configuration-cache directory
        // e.g., from /path/to/build/reports/configuration-cache/abc123/def456/configuration-cache-report.html
        // we want configuration-cache-reports/abc123/def456/configuration-cache-report.html
        List<String> pathParts = Splitter.on(IncrementalConfigurationCachePlugin.CONFIGURATION_CACHE_REPORTS_DIR + '/')
                .splitToList(localReportPath);
        if (pathParts.size() <= 1) {
            return Optional.empty();
        }

        String artifactPath =
                IncrementalConfigurationCachePlugin.CIRCLE_CONFIGURATION_CACHE_REPORTS_DIR + '/' + pathParts.get(1);
        ArtifactLocation artifactLocation =
                getCircleCiArtifacts().resolveArtifactLocation(artifactPath).get();
        return Optional.of(artifactLocation.circleLink());
    }
}
