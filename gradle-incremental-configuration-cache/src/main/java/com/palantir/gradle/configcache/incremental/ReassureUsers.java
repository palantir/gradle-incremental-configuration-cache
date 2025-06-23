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

// CHECKSTYLE:OFF

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.gradle.api.flow.FlowAction;
import org.gradle.api.flow.FlowParameters;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.internal.cc.impl.problems.ConfigurationCacheProblems;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
// CHECKSTYLE:ON

public abstract class ReassureUsers implements FlowAction<ReassureUsers.Params> {
    private static final Logger log = LoggerFactory.getLogger(ReassureUsers.class);

    interface Params extends FlowParameters {
        @Input
        Property<ConfigurationCacheProblems> getProblems();
    }

    @Override
    public final void execute(Params params) {
        if (hasConfigurationCacheProblems(params.getProblems().get())) {
            log.warn(
                    """
                            [IncrementalConfigurationCachePlugin] ⚠️ Configuration Cache is being rolled out.
                            You may see Configuration Cache problems/warnings for some tasks during this process.
                            These issues will be addressed as support for the configuration cache is improved.""");
        }
    }

    /**
     * Use reflection to access Gradle's internal state, to check if there are problems with the Configuration Cache.
     */
    public static boolean hasConfigurationCacheProblems(Object configCacheProblems) {
        try {
            Class<?> configCacheProblemsClass = configCacheProblems.getClass();
            Field summarizerField = configCacheProblemsClass.getDeclaredField("summarizer");
            summarizerField.setAccessible(true); // Bypass access checks
            Object summarizer = summarizerField.get(configCacheProblems);

            Class<?> summaryClass = summarizer.getClass();
            Method getMethod = summaryClass.getDeclaredMethod("get");
            getMethod.setAccessible(true);
            Object summary = getMethod.invoke(summarizer);

            Class<?> summaryObjClass = summary.getClass();
            Field problemCountField = summaryObjClass.getDeclaredField("problemCount");
            problemCountField.setAccessible(true);
            int problemCount = (int) problemCountField.get(summary);

            return problemCount > 0;
        } catch (Exception e) {

            throw new RuntimeException(e);
        }
    }
}
