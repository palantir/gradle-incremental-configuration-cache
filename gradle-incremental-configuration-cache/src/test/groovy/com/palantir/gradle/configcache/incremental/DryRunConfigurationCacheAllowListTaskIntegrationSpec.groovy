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
            tasks.named('dryRunConfigurationCacheAllowList') {
                initScript.set(file('.gradle-test-kit/init.gradle').absolutePath)
            }
            tasks.named('writeConfigurationCacheAllowList') {
                initScript.set(file('.gradle-test-kit/init.gradle').absolutePath)
            }
        '''.stripIndent(true)

        // Put environment-variables in testing mode
        file('gradle.properties') << '''
            __TESTING=true
        '''.stripIndent(true)
    }

    def 'validates empty task list'() {
        given:
        file('gradle/configuration-cache-allowed-tasks') << ''
        file('gradle/configuration-cache-allowed-tasks.lock') << ''

        when:
        def result = runTasksWithConfigurationCache(true, false, 'dryRunConfigurationCacheAllowList', '--info')

        then:
        result.task(':dryRunConfigurationCacheAllowList').outcome == TaskOutcome.SUCCESS
        result.output.contains('No tasks to validate')
    }

    def 'validates compatible tasks successfully'() {
        given:
        // We must have dryRunConfigurationCacheAllowList in the allow list to allow
        // dryRunConfigurationCacheAllowList to run with configuration cache
        file('gradle/configuration-cache-allowed-tasks') << '''
            :compileJava
            :processResources
            :dryRunConfigurationCacheAllowList
            :classes
            :jar
            :dryRunConfigurationCacheAllowList
        '''.stripIndent(true)

        // Lock file has different set of tasks
        file('gradle/configuration-cache-allowed-tasks.lock') << '''
            :compileJava
            :compileTestJava
            :test
            :dryRunConfigurationCacheAllowList
        '''.stripIndent(true)

        when:
        def result = runTasksWithConfigurationCache('dryRunConfigurationCacheAllowList', '--info')

        then:
        result.tasks(TaskOutcome.SUCCESS)*.path.contains(':dryRunConfigurationCacheAllowList')
        result.output.contains('Validating that 3 tasks run with the configuration cache')
        result.output.contains('All 3 tasks passed configuration cache validation')
    }

    def 'fails validation for incompatible tasks'() {
        given:
        file('gradle/configuration-cache-allowed-tasks.lock') << ''
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
        def result = runTasksAndFail('dryRunConfigurationCacheAllowList')

        then:
        result.tasks(TaskOutcome.FAILED)*.path.contains(':dryRunConfigurationCacheAllowList')
        result.output.contains('CONFIGURATION CACHE ALLOW LIST VALIDATION FAILED')
    }

    def 'fails validation for incompatible tasks on CI creates report'() {
        given:
        file('gradle/configuration-cache-allowed-tasks.lock') << ''
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
        def result = runTasksAndFail('dryRunConfigurationCacheAllowList',
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
    }

    def 'validation task is hooked into check task'() {
        given:
        file('gradle/configuration-cache-allowed-tasks') << ''
        file('gradle/configuration-cache-allowed-tasks.lock') << ''
        when:
        def result = runTasks('check', '--dry-run')

        then:
        result.output.contains(':dryRunConfigurationCacheAllowList')
    }
}
