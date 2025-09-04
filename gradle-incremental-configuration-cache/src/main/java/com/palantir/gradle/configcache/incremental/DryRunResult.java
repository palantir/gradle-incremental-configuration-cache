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

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public sealed interface DryRunResult permits DryRunResult.Success, DryRunResult.Failure {

    record Success(Set<String> tasks) implements DryRunResult {
        public Success {
            Objects.requireNonNull(tasks, "tasks must not be null");
        }
    }

    record Failure(String errorOutput) implements DryRunResult {
        public Failure {
            Objects.requireNonNull(errorOutput, "errorOutput must not be null");
        }
    }

    static DryRunResult success(Set<String> tasks) {
        return new Success(tasks);
    }

    static DryRunResult failure(String errorOutput) {
        return new Failure(errorOutput);
    }

    default boolean isSuccess() {
        return this instanceof Success;
    }

    default boolean isFailure() {
        return this instanceof Failure;
    }

    default Optional<Set<String>> maybeTasks() {
        return this instanceof Success s ? Optional.of(s.tasks()) : Optional.empty();
    }

    default Optional<String> maybeErrorOutput() {
        return this instanceof Failure f ? Optional.of(f.errorOutput()) : Optional.empty();
    }
}
