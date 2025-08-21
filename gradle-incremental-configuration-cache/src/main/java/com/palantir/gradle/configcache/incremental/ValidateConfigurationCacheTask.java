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

import com.palantir.gradle.utils.exec.GradleExec;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.gradle.api.DefaultTask;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.TaskAction;

public abstract class ValidateConfigurationCacheTask extends DefaultTask {

    @Input
    public abstract SetProperty<String> getAllowedTasks();

    @Nested
    protected abstract GradleExec getExec();

    @TaskAction
    public final void validate() {
        Set<String> tasks = getAllowedTasks().get();
        if (tasks.isEmpty()) {
            getLogger().lifecycle("No tasks to validate for configuration cache");
            return;
        }

        List<String> commandLine = new ArrayList<>();
        commandLine.add("./gradlew");
        commandLine.addAll(tasks.stream().sorted().toList());
        commandLine.add("--dry-run");
        commandLine.add("--configuration-cache");

        getExec()
                .exec(execSpec -> execSpec.commandLine(commandLine))
                .mapFailure(result -> new RuntimeException(
                        "Configuration cacheable tasks from the allow list failed:" + result.stdErr()))
                .get();
    }
}
