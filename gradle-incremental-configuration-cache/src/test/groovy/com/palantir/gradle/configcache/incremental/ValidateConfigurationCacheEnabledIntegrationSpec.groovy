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

class ValidateConfigurationCacheEnabledIntegrationSpec  extends ConfigurationCacheSpec {

    def setup() {
        initGitRepo()

        // language=gradle
        buildFile << '''
            apply plugin: 'com.palantir.incremental-configuration-cache'
            apply plugin: 'java-library'
            
            tasks.named('validateConfigurationCacheEnabledTasks') {
                initScript.set(file('.gradle-test-kit/init.gradle').absolutePath)
            }
        '''.stripIndent(true)

        def circleArtifactsDir = new File(projectDir, 'circle-artifacts')
        file('gradle.properties') << """
            org.gradle.configuration-cache=true
            __TESTING=true
            __TESTING_CIRCLECI=true
            __TESTING_CIRCLE_ARTIFACTS=$circleArtifactsDir.absolutePath
            __TESTING_CIRCLE_PROJECT_USERNAME=test-username
            __TESTING_CIRCLE_PROJECT_REPONAME=test-repo
            __TESTING_CIRCLE_BUILD_NUM=123
            __TESTING_CIRCLE_NODE_INDEX=0
            __TESTING_CIRCLE_WORKFLOW_JOB_ID=123
        """.stripIndent(true)

        // We must have validateConfigurationCacheEnabledTasks in the allow list to allow
        // validateConfigurationCacheEnabledTasks to run with configuration cache
        file('gradle/configuration-cache-allowed-tasks.lock') << '''
            :validateConfigurationCacheEnabledTasks
        '''.stripIndent(true)

        commitChanges()
    }

    def initGitRepo() {
        executeCommand("git", "init")
        executeCommand("git", "config", "--local", "user.email", "test@example.com")
        executeCommand("git", "config", "--local", "user.name", "Test User")
        executeCommand("git", "add", ".")
        executeCommand("git", "commit", "--no-gpg-sign", "-m", "Initial commit")
    }

    def commitChanges() {
        executeCommand("git", "add", ".")
        executeCommand("git", "commit", "--no-gpg-sign", "-m", "Commit")
    }

    def executeCommand(String... command) {
        def process = new ProcessBuilder(command)
                .directory(projectDir)
                .start()
        process.waitFor()
        if (process.exitValue() != 0) {
            throw new RuntimeException("Command failed: ${command.join(' ')}\n" +
                    "Output: ${process.inputStream.text}\n" +
                    "Error: ${process.errorStream.text}")
        }
    }

    def 'validates empty task list'() {
        given:
        file('gradle/configuration-cache-allowed-tasks') << ''
        commitChanges()

        when:
        def result = runTasksWithConfigurationCache(true, false, 'validateConfigurationCacheEnabledTasks', '--info')

        then:
        result.tasks(TaskOutcome.SUCCESS)*.path.contains(':validateConfigurationCacheEnabledTasks')
        result.output.contains('No tasks to run')
    }

    def 'locally does nothing'() {
        given:
        file('gradle/configuration-cache-allowed-tasks') << ''
        file('gradle.properties').text = 'org.gradle.configuration-cache=true'

        when:
        def result = runTasksAndFailWithConfigurationCache('validateConfigurationCacheEnabledTasks')

        then:
        result.output.contains("Task with name 'validateConfigurationCacheEnabledTasks' not found in root project")
    }

    def 'validates compatible tasks successfully'() {
        given:
        file('gradle/configuration-cache-allowed-tasks') << '''
            :compileJava
            :processResources
        '''.stripIndent(true)
        commitChanges()

        when:
        def result = runTasksWithConfigurationCache('validateConfigurationCacheEnabledTasks', '--info')

        then:
        result.tasks(TaskOutcome.SUCCESS)*.path.contains(':validateConfigurationCacheEnabledTasks')
    }

    def 'fails validation for incompatible tasks'() {
        given:
        file('gradle/configuration-cache-allowed-tasks') << '''
            :problematicTask
            :validateConfigurationCacheEnabledTasks
        '''.stripIndent(true)

        // language=gradle
        buildFile << '''
            tasks.register('problematicTask'){
                def proj = project
                doLast {
                    println "Project: ${proj.name}"
                }
            }
        '''.stripIndent(true)
        commitChanges()

        when:
        def result = runTasksAndFailWithConfigurationCache('validateConfigurationCacheEnabledTasks')

        then:
        result.tasks(TaskOutcome.FAILED)*.path.contains(':validateConfigurationCacheEnabledTasks')
        result.output.contains('CONFIGURATION CACHE ALLOW LIST VALIDATION FAILED')

        def report = new File(projectDir, 'circle-artifacts/configuration-cache-validation-report/validation-report.txt')
        report.exists()

        report.text.contains("1 problem was found storing the configuration cache.")
    }

    def 'fails validation for tasks with execution time configuration cache issues'() {
        given:
        file('gradle/configuration-cache-allowed-tasks') << '''
            :badExecutionTask
            :validateConfigurationCacheEnabledTasks
        '''.stripIndent(true)

        // language=gradle
        buildFile << '''
            tasks.register('badExecutionTask') {
                inputs.property('foo', 'bar')
                doLast {
                    def file = new File(project.buildDir, "undeclared.txt")
                    file.text
                }
            }
        '''.stripIndent(true)
        commitChanges()

        when:
        def result = runTasksAndFailWithConfigurationCache('validateConfigurationCacheEnabledTasks')

        then:
        result.tasks(TaskOutcome.FAILED)*.path.contains(':validateConfigurationCacheEnabledTasks')
        result.output.contains('CONFIGURATION CACHE ALLOW LIST VALIDATION FAILED')

        def report = new File(projectDir, 'circle-artifacts/configuration-cache-validation-report/validation-report.txt')
        report.exists()

        report.text.contains("1 problem was found storing the configuration cache.")
    }

    def 'validation task is hooked into check task'() {
        given:
        file('gradle/configuration-cache-allowed-tasks') << ''
        commitChanges()

        when:
        def result = runTasks('check', '--dry-run')

        then:
        result.output.contains(':validateConfigurationCacheEnabledTasks')
    }

    def 'validateConfigurationCacheEnabledTasks is up-to-date on second run for success'() {
        given:
        file('gradle/configuration-cache-allowed-tasks') << '''
            :compileJava
            :processResources
        '''.stripIndent(true)
        commitChanges()

        when:
        def firstRun = runTasksWithConfigurationCache('validateConfigurationCacheEnabledTasks')

        then:
        firstRun.tasks(TaskOutcome.SUCCESS)*.path.contains(':validateConfigurationCacheEnabledTasks')

        when:
        def secondRun = runTasksWithConfigurationCache('validateConfigurationCacheEnabledTasks')

        then:
        secondRun.tasks(TaskOutcome.UP_TO_DATE)*.path.contains(':validateConfigurationCacheEnabledTasks')
    }
}
