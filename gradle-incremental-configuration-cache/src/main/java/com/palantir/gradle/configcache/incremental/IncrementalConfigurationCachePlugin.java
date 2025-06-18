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
import java.nio.file.Path;
import java.util.Set;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IncrementalConfigurationCachePlugin implements Plugin<Project> {
    private static final Logger log = LoggerFactory.getLogger(IncrementalConfigurationCachePlugin.class);

    public static final Path ALLOW_LIST_FILE = Path.of("gradle/configuration-cache-allowed-tasks");
    private static final String ALLOW_LIST_INFO = "What is the configuration cache allow list?: "
            + "https://github.com/palantir/gradle-incremental-configuration-cache/blob/develop/README.md#motivation";

    @Override
    public final void apply(Project project) {
        if (!project.getRootProject().equals(project)) {
            throw new GradleException("Must be applied only to root project");
        }

        Path allowListPath = project.getRootProject().getProjectDir().toPath().resolve(ALLOW_LIST_FILE);
        if (!Files.exists(allowListPath)) {
            throw new GradleException(
                    String.format("Configuration cache allow list not found at %s\n%s", allowListPath, ALLOW_LIST_INFO)
            );
        }

        AllowListFile allowList = new AllowListFile(allowListPath);
        try {
            Set<String> enabledTasks = allowList.loadAllowedTasks();
            project.getAllprojects().forEach(proj -> proj.getTasks().configureEach(task -> {
                if (!enabledTasks.contains(task.getPath())) {
                    task.notCompatibleWithConfigurationCache(String.format(
                            "Configuration cache is not enabled for this task, as it was not included in %s",
                            ALLOW_LIST_FILE));
                }
            }));
        } catch (IOException e) {
            throw new GradleException(
                    String.format("Error reading the allow list at %s\n%s", allowListPath, ALLOW_LIST_INFO)
            );
        }

        log.warn("""
                [IncrementalConfigurationCachePlugin] ⚠️ Configuration Cache is being rolled out incrementally.
                You may see Configuration Cache problems/warnings for some tasks during this process.
                These issues will be addressed as support for the configuration cache is improved in tasks.
                """
        );
    }
}
