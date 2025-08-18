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

import groovy.io.FileType
import nebula.test.IntegrationTestKitSpec


class IncrementalConfigurationCacheTest extends IntegrationTestKitSpec {
    def setup() {
        definePluginOutsideOfPluginBlock = true
        keepFiles = true

        // language=gradle
        buildFile << '''
            apply plugin: 'com.palantir.incremental-configuration-cache'
            apply plugin: 'java-library'
        '''.stripIndent(true)

        // Put environment-variables in testing mode
        file('gradle.properties') << '''
            __TESTING=true
        '''.stripIndent(true)
    }

    def "blows up if allow list file does not exist"() {
        when:
        def buildResult = createRunner('classes', '--configuration-cache').buildAndFail()
        def output = buildResult.output

        then:
        output.contains('Configuration cache allowed tasks file not found')
    }

    def "blows up if applied to non root project"() {
        file('subproject/build.gradle') << '''
            apply plugin: 'com.palantir.incremental-configuration-cache'
            apply plugin: 'java-library'
        '''.stripIndent(true)

        file("gradle/configuration-cache-allowed-tasks") << '''
            :compileJava
            :processResources
            :classes
        '''.stripIndent(true)

        // language=gradle
        settingsFile << '''
            include 'subproject'
        '''.stripIndent(true)

        when:
        def buildResult = createRunner('classes', '--configuration-cache').buildAndFail()
        def output = buildResult.output

        then:
        output.contains('Must be applied only to root project')
    }


    def "tasks in allow list run with config cache"() {
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

    def 'blows up if gradleVersion lower than 8.12.0'() {
        file("gradle/configuration-cache-allowed-tasks") << ''

        given:
        def runner = createRunner('--info').withGradleVersion(gradleVersion)

        when:
        def buildResult = runner.buildAndFail()
        def output = buildResult.output

        then:
        output.contains("Cannot apply IncrementalConfigurationCachePlugin with Gradle version older than Gradle 8.12.0")

        where:
        gradleVersion << ["7.6.5", "8.11.1"]
    }

    def "does not blow if if gradleVersion is at least 8.12.0"() {
        file("gradle/configuration-cache-allowed-tasks") << ''

        given:
        def runner = createRunner('--info').withGradleVersion(gradleVersion)

        expect:
        runner.build()

        where:
        gradleVersion << ["8.12.1", "8.14.2"]
    }

    def 'copies configuration cache reports to circle artifacts'() {
        file('gradle/configuration-cache-allowed-tasks') << ''

        // language=Gradle
        buildFile << '''
            // Fail configuration cache at configuration time
            'ls'.execute()
        '''.stripIndent(true)

        when: 'configuration cache is enabled, running on circleci'
        def output = createRunner(
                '--info',
                '--configuration-cache',
                '-P__TESTING_CIRCLECI=true',
                '-P__TESTING_CIRCLE_ARTIFACTS=' + getProjectDir().toPath().resolve('circle-artifacts')
        ).buildAndFail().output

        then: 'it fails due to configuration cache problems, a configuration cache report is in the circle artifacts directory'
        output.contains('Configuration cache problems found in this build')


        def circleArtifactsReports = new File(projectDir, 'circle-artifacts/configuration-cache-reports')
        circleArtifactsReports.exists()

        def reports = []
        circleArtifactsReports.traverse(type: FileType.FILES, maxDepth: 4) { reports.add(it) }

        reports.size() == 1
    }

    def 'outputs configuration cache reports to normal location when running locally'() {
        file('gradle/configuration-cache-allowed-tasks') << ''

        // language=Gradle
        buildFile << '''
            // Fail configuration cache at configuration time
            'ls'.execute()
        '''.stripIndent(true)

        when: 'configuration cache is enabled, running on circleci'
        def output = createRunner(
                '--info',
                '--configuration-cache',
        ).buildAndFail().output

        then: 'it fails due to configuration cache problems, a configuration cache report is in the usual location'
        output.contains('Configuration cache problems found in this build')

        def circleArtifactsReports = new File(projectDir, 'build/reports/configuration-cache')
        circleArtifactsReports.exists()

        def reports = []
        circleArtifactsReports.traverse(type: FileType.FILES, maxDepth: 4) { reports.add(it) }

        reports.size() == 1
    }

    private boolean runTasksWithConfigurationCache(String... tasks) {
        def firstRun = createRunner(tasks + ['--configuration-cache'] as String[]).build()
        def firstOutput = firstRun.output
        assert firstOutput.contains('Configuration cache entry stored.'),
                "Expected first run to store configuration cache, but output was: ${firstRun.output}"

        def secondRun = createRunner(tasks + ['--configuration-cache'] as String[]).build()
        def secondOutput = secondRun.output
        assert secondOutput.contains('Configuration cache entry reused.'),
                "Expected second run to reuse configuration cache, but output was: ${secondRun.output}"

        return true
    }
}
