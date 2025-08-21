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

class ValidateConfigurationCacheTaskIntegrationSpec extends ConfigurationCacheSpec {

    def setup() {
        // language=gradle
        buildFile << '''
            apply plugin: 'com.palantir.incremental-configuration-cache'
            apply plugin: 'java-library'
            
            tasks.named('validateConfigurationCacheAllowList') {
                initScript.set(file('.gradle-test-kit/init.gradle').absolutePath)
            }
        '''.stripIndent(true)
    }

    def 'validates empty task list'() {
        given:
        file('gradle/configuration-cache-allowed-tasks') << ''

        when:
        def result = runTasksWithConfigurationCache(true, false, 'validateConfigurationCacheAllowList')

        then:
        result.task(':validateConfigurationCacheAllowList').outcome == TaskOutcome.SUCCESS
        result.output.contains('No tasks to validate')
    }

    def 'validates compatible tasks successfully'() {
        given:
        // We must have validateConfigurationCacheAllowList in the allow list to allow
        // validateConfigurationCacheAllowList to run with configuration cache
        file('gradle/configuration-cache-allowed-tasks') << '''
            :compileJava
            :processResources
            :validateConfigurationCacheAllowList
        '''.stripIndent(true)

        when:
        def result = runTasksWithConfigurationCache('validateConfigurationCacheAllowList')

        then:
        result.task(':validateConfigurationCacheAllowList').outcome == TaskOutcome.SUCCESS
        result.output.contains('Validating configuration cache for 3 tasks')
        result.output.contains('All 3 tasks passed configuration cache validation')
    }

    def 'fails validation for incompatible tasks'() {
        given:
        file('gradle/configuration-cache-allowed-tasks') << '''
            :problematicTask
            :validateConfigurationCacheAllowList
        '''.stripIndent(true)

        buildFile << '''
        task problematicTask {
            'echo test'.execute() // Configuration cache problem at configuration time
        }
        '''.stripIndent(true)

        when:
        def result = runTasksAndFail('validateConfigurationCacheAllowList')

        then:
        result.task(':validateConfigurationCacheAllowList').outcome == TaskOutcome.FAILED
        result.output.contains('Configuration cache validation failed')
    }

    def 'validation task is hooked into check task'() {
        given:
        file('gradle/configuration-cache-allowed-tasks') << ''
        when:
        def result = runTasks('check', '--dry-run')

        then:
        result.output.contains(':validateConfigurationCacheAllowList')
    }
}
