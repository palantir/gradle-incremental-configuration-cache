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
import java.util.stream.Collectors;
import org.gradle.api.GradleException;


// TODO: maybe add the file version in the first line, for future proofing
public class AllowListFile {
    private Path path;

    public AllowListFile(Path path) {
        this.path = path;

        if (!Files.exists(path)) {
            throw new GradleException("AllowlistFile does not exist at " + path);
        }
    }

    public Set<String> loadAllowedTasks() {
        try {
            return Files.readAllLines(path).stream()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .collect(Collectors.toSet());
        } catch (IOException e) {
            throw new GradleException("Failed to read configuration cache allowed tasks file: " + path, e);
        }
    }
}
