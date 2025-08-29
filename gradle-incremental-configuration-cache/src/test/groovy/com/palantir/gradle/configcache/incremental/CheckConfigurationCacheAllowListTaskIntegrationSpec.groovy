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

class CheckConfigurationCacheAllowListTaskIntegrationSpec extends ConfigurationCacheSpec {

    def setup() {
        // language=gradle
        buildFile << '''
            apply plugin: 'com.palantir.incremental-configuration-cache'
            apply plugin: 'java-library'
            
            tasks.named('checkConfigurationCacheAllowList') {
                initScript.set(file('.gradle-test-kit/init.gradle').absolutePath)
            }
        '''.stripIndent(true)

        // Put environment-variables in testing mode
        file('gradle.properties') << '''
            __TESTING=true
        '''.stripIndent(true)
    }

    def 'validation task is hooked into check task'() {
        given:
        file('gradle/configuration-cache-allowed-tasks') << ''
        file('gradle/configuration-cache-allowed-tasks.lock') << ''
        when:
        def result = runTasks('check', '--dry-run')

        then:
        result.output.contains(':checkConfigurationCacheAllowList')
    }

    def 'checkConfigurationCacheAllowList fails, --fix creates lock file'() {
        given:
        file('gradle/configuration-cache-allowed-tasks') << ''

        when:
        def result = runTasksAndFail('checkConfigurationCacheAllowList')

        then:
        result.output.contains("Run with --fix to create the lock file.")

        when:
        runTasks('checkConfigurationCacheAllowList', '--fix')

        then:
        new File(projectDir, 'gradle/configuration-cache-allowed-tasks.lock').exists()
    }
    
    def 'checkConfigurationCacheAllowList succeeds'() {
        given:
        def tasks = '''
            :classes
            :compileJava
            :processResources
        '''.stripIndent(true)

        file('gradle/configuration-cache-allowed-tasks') << '''
            classes
        '''.stripIndent(true)
        file('gradle/configuration-cache-allowed-tasks.lock') << ''

        when:
        def result = runTasks('checkConfigurationCacheAllowList', '--fix')

        then:
        result.tasks(TaskOutcome.SUCCESS)*.path.contains(':checkConfigurationCacheAllowList')
        file('gradle/configuration-cache-allowed-tasks.lock').text.trim() == tasks.trim()
    }

    def 'fails when lock file has both missing and extra tasks'() {
        given:
        file('gradle/configuration-cache-allowed-tasks') << '''
            :classes
            :compileJava
            :dryRunConfigurationCacheAllowList
            :jar
            :processResources
        '''.stripIndent(true)

        // Lock file has different set of tasks
        file('gradle/configuration-cache-allowed-tasks.lock') << '''
            :compileJava
            :compileTestJava
            :test
            :dryRunConfigurationCacheAllowList
        '''.stripIndent(true)

        when:
        def result = runTasksAndFail('checkConfigurationCacheAllowList')

        then:
        result.tasks(TaskOutcome.FAILED)*.path.contains(':checkConfigurationCacheAllowList')
        result.output.contains('Lock file does not match the tasks that would run')
        result.output.contains('Total tasks in lock file: 4')
        result.output.contains('Total tasks that would execute: 5')

        then:
        runTasks('checkConfigurationCacheAllowList', '--fix')
        file('gradle/configuration-cache-allowed-tasks.lock').text.trim() == file('gradle/configuration-cache-allowed-tasks').text.trim()
    }
}
