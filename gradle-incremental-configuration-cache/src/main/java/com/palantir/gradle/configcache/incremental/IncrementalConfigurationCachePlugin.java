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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IncrementalConfigurationCachePlugin implements Plugin<Project> {
    private static final Logger log = LoggerFactory.getLogger(IncrementalConfigurationCachePlugin.class);

    public static final Path ALLOW_LIST_FILE = Path.of("gradle/configuration-cache-allowed-tasks");

    @Override
    public final void apply(Project project) {
        Path allowlistPath = project.getRootProject().getProjectDir().toPath().resolve(ALLOW_LIST_FILE);
        if (!Files.exists(allowlistPath)) {
            log.warn(
                    "Configuration cache allowed tasks file not found at: {}. "
                            + "All tasks will be marked as incompatible with configuration cache.",
                    allowlistPath.toAbsolutePath()
            );
            return;
        }

        project.afterEvaluate(_proj -> {
            AllowListFile allowList = new AllowListFile(allowlistPath);
            Set<String> enabledTasks = allowList.loadAllowedTasks();

            project.getTasks().configureEach(task -> {
                if (!enabledTasks.contains(task.getPath())) {
                    task.notCompatibleWithConfigurationCache(
                            String.format(
                                    "Configuration cache is not enabled for this task, as it was not included in %s",
                                    ALLOW_LIST_FILE));
                }
            });
        });
    }
}
