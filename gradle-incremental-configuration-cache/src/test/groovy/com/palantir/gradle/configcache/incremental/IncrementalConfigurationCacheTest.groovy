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
import spock.lang.Unroll


class IncrementalConfigurationCacheTest extends IntegrationTestKitSpec {
    def setup() {
        definePluginOutsideOfPluginBlock = true
        keepFiles = true

        // language=gradle
        buildFile << '''
            apply plugin: 'com.palantir.incremental-configuration-cache'
            apply plugin: 'java-library'

            tasks.register('breaksConfigurationCache') {
                doLast {
                    println "This project's name is: " + getProject().name
                }
            }

            tasks.register('supportsConfigurationCache') {
                doLast {
                    println "I am a happy squirrel"
                }
            }
        '''.stripIndent(true)
    }

    def "blows up if allow list file does not exist"() {
        when:
        def buildResult = createRunner('classes', '--configuration-cache').buildAndFail()
        def output = buildResult.output

        then:
        output.contains('Configuration cache allow list file not found')
    }

    def "blows up if applied to non root project"() {
        settingsFile << '''
            include 'subproject'
        '''.stripIndent(true)

        addSubproject("subproject", '''
            apply plugin: 'com.palantir.incremental-configuration-cache'
            apply plugin: 'java-library'
        '''.stripIndent(true))

        file("gradle/configuration-cache-allowed-tasks") << '''
            :compileJava
            :processResources
            :classes
        '''.stripIndent(true)

        when:
        def buildResult = createRunner('classes', '--configuration-cache').buildAndFail()
        def output = buildResult.output

        then:
        output.contains('Must be applied only to root project')
    }


    def "tasks in allow list run with configuration cache"() {
        file("gradle/configuration-cache-allowed-tasks") << '''
            :compileJava
            :processResources
            :classes
        '''.stripIndent(true)

        expect:
        runTasksWithConfigurationCache("classes")
    }

    def "tasks not in allow list don't run with config cache"() {
        file("gradle/configuration-cache-allowed-tasks") << '''
            :compileJava
            :processResources
        '''.stripIndent(true)

        when:
        def buildResult = createRunner('classes', '--configuration-cache').build()
        def output = buildResult.output

        then:
        !output.contains('Configuration cache entry stored.')
    }

    def "reassure user when there are configuration cache problems"() {
        file("gradle/configuration-cache-allowed-tasks") << ""

        when:
        def buildResult = createRunner("breaksConfigurationCache", '--configuration-cache').build()
        def output = buildResult.output

        then:
        output.contains("[IncrementalConfigurationCachePlugin] ⚠️ Configuration Cache is being rolled out")
    }

    def "don't reassure user when there are no configuration cache problems"() {
        file("gradle/configuration-cache-allowed-tasks") << ""

        when:
        def buildResult = createRunner("supportsConfigurationCache", '--configuration-cache').build()
        def output = buildResult.output

        then:
        !output.contains("[IncrementalConfigurationCachePlugin] ⚠️ Configuration Cache is being rolled out")
    }

    def "don't reassure user when there is a build failure"() {
        file("gradle/configuration-cache-allowed-tasks") << ""
        // language=Gradle
        buildFile << '''
            "ls".execute()
        '''.stripIndent(true)

        when:
        def buildResult = createRunner("supportsConfigurationCache", '--configuration-cache').buildAndFail()
        def output = buildResult.output

        then:
        !output.contains("[IncrementalConfigurationCachePlugin] ⚠️ Configuration Cache is being rolled out")
    }

    private void runTasksWithConfigurationCache(String... tasks) {
        def firstRun = createRunner(tasks + ['--configuration-cache'] as String[]).build()
        def firstOutput = firstRun.output
        assert firstOutput.contains('Configuration cache entry stored.'),
                "Expected first run to store configuration cache, but output was: ${firstRun.output}"

        def secondRun = createRunner(tasks + ['--configuration-cache'] as String[]).build()
        def secondOutput = secondRun.output
        assert secondOutput.contains('Configuration cache entry reused.'),
                "Expected second run to reuse configuration cache, but output was: ${secondRun.output}"
    }
}
