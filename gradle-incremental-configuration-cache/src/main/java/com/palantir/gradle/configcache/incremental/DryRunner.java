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
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.inject.Inject;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.tooling.GradleConnectionException;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;

public abstract class DryRunner {
    private static final Logger log = Logging.getLogger(DryRunner.class);
    private static final Pattern DRY_RUN_TASK_PATTERN = Pattern.compile("(:[\\w:-]+)\\s+SKIPPED");

    @Inject
    protected abstract ProjectLayout getProjectLayout();

    public final DryRunResult dryRun(Set<String> tasksToDryRun, List<String> arguments, String initScript) {

        if (tasksToDryRun == null || tasksToDryRun.isEmpty()) {
            log.info("No tasks to dry-run");
            return DryRunResult.success(new TreeSet<>());
        }

        log.info("Dry-running {} tasks", tasksToDryRun.size());

        GradleConnector connector = GradleConnector.newConnector()
                .forProjectDirectory(getProjectLayout().getProjectDirectory().getAsFile());

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream(16 * 1024);

        try (ProjectConnection connection = connector.connect()) {
            connection
                    .newBuild()
                    .withArguments(buildArguments(arguments, initScript))
                    .forTasks(tasksToDryRun.toArray(new String[0]))
                    .setStandardOutput(outputStream)
                    .setStandardError(outputStream)
                    .run();

            log.info("All {} tasks dry-ran successfully", tasksToDryRun.size());

        } catch (GradleConnectionException e) {
            log.info("Failed to run Dry-run tasks", e);
            String error = outputStream.toString(StandardCharsets.UTF_8);
            return DryRunResult.failure(error);
        }

        Set<String> dryRunTasks = parseDryRunResult(outputStream.toString(StandardCharsets.UTF_8));
        return DryRunResult.success(dryRunTasks);
    }

    private static ImmutableList<String> buildArguments(List<String> arguments, String initScript) {
        ImmutableList.Builder<String> argumentsBuilder = ImmutableList.builder();
        argumentsBuilder.add("--dry-run");
        argumentsBuilder.add("--console=plain");
        if (arguments != null) {
            argumentsBuilder.addAll(arguments);
        }
        if (initScript != null && !initScript.isBlank()) {
            argumentsBuilder.add("--init-script=" + initScript);
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
}
