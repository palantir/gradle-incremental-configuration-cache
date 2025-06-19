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
package com.palantir.gradle.configcache.incremental
import nebula.test.IntegrationTestKitSpec


class IncrementalConfigurationCacheTest extends IntegrationTestKitSpec {
    def setup() {
        definePluginOutsideOfPluginBlock = true
        keepFiles = true
    }

    def "blows up if allow list file does not exist"() {
        // language=gradle
        buildFile << '''
            apply plugin: 'com.palantir.incremental-configuration-cache'
            apply plugin: 'java-library'
        '''.stripIndent(true)

        when:
        def buildResult = createRunner('classes', '--configuration-cache').buildAndFail()

        then:
        buildResult.output.contains('Configuration cache allowed tasks file not found')
    }

    def "blows up if applied to non root project"() {
        def subprojectDir = directory('subproject')
        file('build.gradle', subprojectDir) << '''
            apply plugin: 'com.palantir.incremental-configuration-cache'
            apply plugin: 'java-library'
        '''.stripIndent(true)

        directory("gradle")
        file("gradle/configuration-cache-allowed-tasks") << '''
            :compileJava
            :processResources
            :classes
        '''.stripIndent(true)

        // language=gradle
        settingsFile << '''
            include 'subproject'
        '''.stripIndent(true)

        // language=gradle
        buildFile << '''
            apply plugin: 'com.palantir.incremental-configuration-cache'
            apply plugin: 'java-library'
        '''.stripIndent(true)

        when:
        def buildResult = createRunner('classes', '--configuration-cache').buildAndFail()

        then:
        buildResult.output.contains('Must be applied only to root project')
    }


    def "tasks in allow list run with config cache"() {
        directory("gradle")
        file("gradle/configuration-cache-allowed-tasks") << '''
            :compileJava
            :processResources
            :classes
        '''.stripIndent(true)

        // language=gradle
        buildFile << '''
            apply plugin: 'com.palantir.incremental-configuration-cache'
            apply plugin: 'java-library'
        '''.stripIndent(true)

        expect:
        runTasksWithConfigurationCache("classes")
    }

    def "tasks not in allow list don't run with config cache"() {
        directory("gradle")
        file("gradle/configuration-cache-allowed-tasks") << '''
            :compileJava
            :processResources
        '''.stripIndent(true)

        // language=gradle
        buildFile << '''
            apply plugin: 'com.palantir.incremental-configuration-cache'
            apply plugin: 'java-library'
        '''.stripIndent(true)

        when:
        def buildResult = createRunner('classes', '--configuration-cache').build()

        then:
        !buildResult.output.contains('Configuration cache entry stored.')
    }


    private boolean runTasksWithConfigurationCache(String... tasks) {
        def firstRun = createRunner(tasks + ['--configuration-cache'] as String[]).build()
        assert firstRun.output.contains('Configuration cache entry stored.'),
                "Expected first run to store configuration cache, but output was: ${firstRun.output}"

        def secondRun = createRunner(tasks + ['--configuration-cache'] as String[]).build()
        assert secondRun.output.contains('Configuration cache entry reused.'),
                "Expected second run to reuse configuration cache, but output was: ${secondRun.output}"

        return true
    }
}