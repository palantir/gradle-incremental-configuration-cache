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
import java.util.Set;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskAction;
import org.gradle.tooling.GradleConnectionException;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;

public abstract class ValidateConfigurationCacheTask extends DefaultTask {

    @Input
    public abstract SetProperty<String> getAllowedTasks();

    @Input
    @Optional
    public abstract Property<String> getInitScript();

    @Inject
    protected abstract ProjectLayout getProjectLayout();

    @TaskAction
    public final void validate() {
        Set<String> tasks = getAllowedTasks().get();

        if (tasks.isEmpty()) {
            getLogger().info("No tasks to validate");
            return;
        }

        getLogger().info("Validating configuration cache for {} tasks", tasks.size());

        GradleConnector connector = GradleConnector.newConnector()
                .forProjectDirectory(getProjectLayout().getProjectDirectory().getAsFile());

        try (ProjectConnection connection = connector.connect()) {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

            ImmutableList.Builder<String> argumentsBuilder = ImmutableList.builder();
            argumentsBuilder.add("--dry-run", "--configuration-cache");

            // GradleConnector runs builds in a separate process, so we must explicitly pass init scripts.
            // This is required for Nebula tests, which rely on init scripts for test setup.
            if (getInitScript().isPresent() && !getInitScript().get().isBlank()) {
                argumentsBuilder.add("--init-script=" + getInitScript().get());
            }

            try {
                connection
                        .newBuild()
                        .forTasks(tasks.toArray(new String[0]))
                        .withArguments(argumentsBuilder.build())
                        .setStandardOutput(outputStream)
                        .setStandardError(outputStream)
                        .run();

                getLogger().info("All {} tasks passed configuration cache validation", tasks.size());

            } catch (GradleConnectionException e) {
                throw new RuntimeException(
                        "Configuration cache validation failed. See error output for details. \n" + outputStream);
            }
        }
    }
}
