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

import com.palantir.gradle.plugintesting.ConfigurationCacheSpec
import org.gradle.testkit.runner.TaskOutcome

class DryRunConfigurationCacheAllowListTaskIntegrationSpec extends ConfigurationCacheSpec {

    def setup() {
        // language=gradle
        buildFile << '''
            apply plugin: 'com.palantir.incremental-configuration-cache'
            apply plugin: 'java-library'
            
            tasks.named('dryRunConfigurationCacheAllowList') {
                initScript.set(file('.gradle-test-kit/init.gradle').absolutePath)
            }
        '''.stripIndent(true)

        // Put environment-variables in testing mode
        file('gradle.properties') << '''
            __TESTING=true
        '''.stripIndent(true)

        // need lock file with :dryRunConfigurationCacheAllowList so it is not marked incompatible
        file('gradle/configuration-cache-allowed-tasks.lock') << '''
            :dryRunConfigurationCacheAllowList
        '''.stripIndent(true)
    }

    def 'dry run empty task list'() {
        given:
        file('gradle/configuration-cache-allowed-tasks') << ''

        when:
        def result = runTasksWithConfigurationCacheAndCheck('dryRunConfigurationCacheAllowList', '--info')

        then:
        result.task(':dryRunConfigurationCacheAllowList').outcome == TaskOutcome.SUCCESS
        result.output.contains('No tasks to validate')

        and:
        def outputFile = new File(projectDir, 'build/tmp/dryRunConfigurationCacheAllowList/dry-run-tasks.txt')
        outputFile.exists()
        outputFile.text.trim() == ''
    }

    def 'dry run compatible tasks successfully'() {
        given:
        file('gradle/configuration-cache-allowed-tasks') << '''
            :classes
            :compileJava
            :dryRunConfigurationCacheAllowList
            :processResources
        '''.stripIndent(true)

        when:
        def result = runTasksWithConfigurationCacheAndCheck('dryRunConfigurationCacheAllowList', '--info')

        then:
        result.task(':dryRunConfigurationCacheAllowList').outcome == TaskOutcome.SUCCESS
        result.output.contains('All 4 tasks passed configuration cache validation')

        and:
        def outputFile = new File(projectDir, 'build/tmp/dryRunConfigurationCacheAllowList/dry-run-tasks.txt')
        outputFile.exists()
        outputFile.text.trim() == file('gradle/configuration-cache-allowed-tasks').text.trim()
    }

    def 'fails dry run for incompatible tasks'() {
        given:
        file('gradle/configuration-cache-allowed-tasks') << '''
            :problematicTask
            :dryRunConfigurationCacheAllowList
        '''.stripIndent(true)

        buildFile << '''
            task problematicTask {
                'echo test'.execute() // Configuration cache problem at configuration time
            }
        '''.stripIndent(true)

        when:
        def result = runTasksWithConfigurationCache(true, true, 'dryRunConfigurationCacheAllowList')

        then:
        result.task(':dryRunConfigurationCacheAllowList').outcome == TaskOutcome.FAILED
        result.output.contains('CONFIGURATION CACHE ALLOW LIST VALIDATION FAILED')

        and:
        new File(projectDir, 'build/tmp/dryRunConfigurationCacheAllowList/dry-run-tasks.txt').exists()
    }

    def 'fails dry run for incompatible tasks on CI creates report'() {
        given:
        file('gradle/configuration-cache-allowed-tasks') << '''
            :problematicTask
            :dryRunConfigurationCacheAllowList
        '''.stripIndent(true)

        buildFile << '''
            task problematicTask {
                'echo test'.execute() // Configuration cache problem at configuration time
            }
        '''.stripIndent(true)

        when:
        def result = runTasksWithConfigurationCache(true, true, 'dryRunConfigurationCacheAllowList',
                '-P__TESTING_CIRCLECI=true',
                '-P__TESTING_CIRCLE_ARTIFACTS=' + getProjectDir().toPath().resolve('circle-artifacts'),
                '-P__TESTING_CIRCLE_PROJECT_USERNAME=test-username',
                '-P__TESTING_CIRCLE_PROJECT_REPONAME=test-repo',
                '-P__TESTING_CIRCLE_BUILD_NUM=123',
                '-P__TESTING_CIRCLE_NODE_INDEX=0',
                '-P__TESTING_CIRCLE_WORKFLOW_JOB_ID=123'
        )

        then:
        result.tasks(TaskOutcome.FAILED)*.path.contains(':dryRunConfigurationCacheAllowList')
        result.output.contains('CONFIGURATION CACHE ALLOW LIST VALIDATION FAILED')

        def report = new File(projectDir, 'circle-artifacts/configuration-cache-validation-report/validation-report.txt')
        report.exists()

        report.text.contains("1 problem was found storing the configuration cache.")

        and:
        new File(projectDir, 'build/tmp/dryRunConfigurationCacheAllowList/dry-run-tasks.txt').exists()
    }

    def 'dryRunConfigurationCacheAllowList is hooked into check task'() {
        given:
        file('gradle/configuration-cache-allowed-tasks') << ''
        when:
        def result = runTasks('check', '--dry-run')

        then:
        result.output.contains(':dryRunConfigurationCacheAllowList')
    }
}
