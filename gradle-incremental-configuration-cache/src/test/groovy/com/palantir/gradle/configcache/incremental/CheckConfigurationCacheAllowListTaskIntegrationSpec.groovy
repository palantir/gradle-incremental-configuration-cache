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
            
            tasks.named('dryRunConfigurationCacheAllowList') {
                initScript.set(file('.gradle-test-kit/init.gradle').absolutePath)
            }
        '''.stripIndent(true)

        // Put environment-variables in testing mode
        file('gradle.properties') << '''
            __TESTING=true
        '''.stripIndent(true)
    }

    def 'checkConfigurationCacheAllowListLock is hooked into check task'() {
        given:
        file('gradle/configuration-cache-allowed-tasks') << ''
        file('gradle/configuration-cache-allowed-tasks.lock') << ''
        when:
        def result = runTasks('check', '--dry-run')

        then:
        result.output.contains(':checkConfigurationCacheAllowListLock')
    }

    def 'checkConfigurationCacheAllowListLock fails, --fix creates lock file'() {
        given:
        file('gradle/configuration-cache-allowed-tasks') << ''

        when:
        def result = runTasksWithConfigurationCache(true, true,'checkConfigurationCacheAllowListLock')

        then:
        result.output.contains("Run `./gradlew :checkConfigurationCacheAllowListLock --fix` to create the lock file.")

        when:
        runTasks('checkConfigurationCacheAllowListLock', '--fix')

        then:
        new File(projectDir, 'gradle/configuration-cache-allowed-tasks.lock').exists()
    }

    def 'checkConfigurationCacheAllowListLock passes when lock file is correct'() {
        given:
        def tasks = '''
            :classes
            :compileJava
            :checkConfigurationCacheAllowListLock
            :dryRunConfigurationCacheAllowList
            :processResources
        '''.stripIndent(true)

        file('gradle/configuration-cache-allowed-tasks') << '''
            classes
            checkConfigurationCacheAllowListLock
        '''.stripIndent(true)
        file('gradle/configuration-cache-allowed-tasks.lock') << tasks

        when:
        def result = runTasksWithConfigurationCacheAndCheck('checkConfigurationCacheAllowListLock')

        then:
        result.tasks(TaskOutcome.SUCCESS)*.path.contains(':checkConfigurationCacheAllowListLock')
    }

    def 'checkConfigurationCacheAllowListLock --fix populates the allow list lock'() {
        given:
        def tasks = '''
            :checkConfigurationCacheAllowListLock
            :classes
            :compileJava
            :dryRunConfigurationCacheAllowList
            :processResources
        '''.stripIndent(true)

        file('gradle/configuration-cache-allowed-tasks') << '''
            classes
            checkConfigurationCacheAllowListLock
        '''.stripIndent(true)
        file('gradle/configuration-cache-allowed-tasks.lock') << ''

        when:
        def result = runTasksWithConfigurationCache(true, false,'checkConfigurationCacheAllowListLock', '--fix')

        then:
        result.tasks(TaskOutcome.SUCCESS)*.path.contains(':checkConfigurationCacheAllowListLock')
        file('gradle/configuration-cache-allowed-tasks.lock').text.trim() == tasks.trim()
    }

    def 'fails when lock file has both missing and extra tasks'() {
        given:
        file('gradle/configuration-cache-allowed-tasks') << '''
            classes
            checkConfigurationCacheAllowListLock
            :jar
        '''.stripIndent(true)

        // Lock file has different set of tasks
        file('gradle/configuration-cache-allowed-tasks.lock') << '''
            :compileJava
            :compileTestJava
            :test
            :dryRunConfigurationCacheAllowList
            :checkConfigurationCacheAllowListLock
        '''.stripIndent(true)

        when:
        def result = runTasksAndFailWithConfigurationCache('checkConfigurationCacheAllowListLock')

        then:
        result.tasks(TaskOutcome.FAILED)*.path.contains(':checkConfigurationCacheAllowListLock')
        result.output.contains('Lock file does not match the tasks that would run')
        result.output.contains('Total tasks in lock file: 5')
        result.output.contains('Total tasks that would execute: 6')

        then:
        runTasksWithConfigurationCache('checkConfigurationCacheAllowListLock', '--fix')
        file('gradle/configuration-cache-allowed-tasks.lock').text.trim() == '''
            :checkConfigurationCacheAllowListLock
            :classes
            :compileJava
            :dryRunConfigurationCacheAllowList
            :jar
            :processResources
            '''.stripIndent(true).trim()
    }


    def 'checkConfigurationCacheAllowListLock fails when sub-project added then fix works'() {
        given:
        def tasks = '''
            :classes
            :compileJava
            :checkConfigurationCacheAllowListLock
            :dryRunConfigurationCacheAllowList
            :processResources
        '''.stripIndent(true)

        file('gradle/configuration-cache-allowed-tasks') << '''
            classes
            checkConfigurationCacheAllowListLock
        '''.stripIndent(true)
        file('gradle/configuration-cache-allowed-tasks.lock') << tasks

        addSubproject("subproject")
        //language=gradle
        file('subproject/build.gradle') << '''
            apply plugin: 'java-library'
        '''.stripIndent(true)

        when:
        def result = runTasksAndFailWithConfigurationCache('checkConfigurationCacheAllowListLock')

        then:
        result.tasks(TaskOutcome.FAILED)*.path.contains(':checkConfigurationCacheAllowListLock')
        result.output.contains('Lock file does not match the tasks that would run')

        when:
        def fixResult = runTasksWithConfigurationCache('checkConfigurationCacheAllowListLock', '--fix')

        then:
        fixResult.tasks(TaskOutcome.SUCCESS)*.path.contains(':checkConfigurationCacheAllowListLock')
        def lockContent = file('gradle/configuration-cache-allowed-tasks.lock').text
        lockContent.contains(':classes')
        lockContent.contains(':subproject:classes')
        lockContent.contains(':subproject:compileJava')
    }
}
