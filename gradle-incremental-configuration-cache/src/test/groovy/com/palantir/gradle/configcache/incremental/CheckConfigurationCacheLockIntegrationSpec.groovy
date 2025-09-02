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

class CheckConfigurationCacheLockIntegrationSpec extends ConfigurationCacheSpec {
    def setup() {
        // language=gradle
        buildFile << '''
            apply plugin: 'com.palantir.incremental-configuration-cache'
            apply plugin: 'java-library'
            
            tasks.named('checkConfigurationCacheLock') {
                initScript.set(file('.gradle-test-kit/init.gradle').absolutePath)
            }
        '''.stripIndent(true)

        file('gradle.properties') << '''
            org.gradle.configuration-cache=true
        '''.stripIndent(true)
    }

    def 'checkConfigurationCacheLock is hooked into check task'() {
        given:
        file('gradle/configuration-cache-allowed-tasks') << ''

        when:
        def result = runTasks('check', '--dry-run')

        then:
        result.output.contains(':checkConfigurationCacheLock')
    }

    def 'checkConfigurationCacheLock fails, --fix creates lock file'() {
        given:
        file('gradle/configuration-cache-allowed-tasks') << ''

        when:
        def result = runTasksWithConfigurationCache(true, true,'checkConfigurationCacheLock')

        then:
        result.output.contains("Run `./gradlew :checkConfigurationCacheLock --fix` to create the lock file.")

        when:
        runTasks('checkConfigurationCacheLock', '--fix')

        then:
        new File(projectDir, 'gradle/configuration-cache-allowed-tasks.lock').exists()
    }

    def 'checkConfigurationCacheLock passes when lock file is correct'() {
        given:
        def tasks = '''
            :classes
            :compileJava
            :checkConfigurationCacheLock
            :processResources
        '''.stripIndent(true)

        file('gradle/configuration-cache-allowed-tasks') << '''
            classes
            checkConfigurationCacheLock
        '''.stripIndent(true)
        file('gradle/configuration-cache-allowed-tasks.lock') << tasks

        when:
        def result = runTasksWithConfigurationCacheAndCheck('checkConfigurationCacheLock')

        then:
        result.tasks(TaskOutcome.SUCCESS)*.path.contains(':checkConfigurationCacheLock')
    }

    def 'checkConfigurationCacheLock passes when correct even if configuration cache incompatible task present'() {
        given:
        // language=gradle
        buildFile << '''
            tasks.register('problematicTask'){
                def proj = project
                doLast {
                    println "Project: ${proj.name}"
                }
            }
        '''.stripIndent(true)

        def tasks = '''
            :classes
            :compileJava
            :checkConfigurationCacheLock
            :processResources
            :problematicTask
        '''.stripIndent(true)

        file('gradle/configuration-cache-allowed-tasks') << '''
            classes
            checkConfigurationCacheLock
            problematicTask
        '''.stripIndent(true)
        file('gradle/configuration-cache-allowed-tasks.lock') << tasks

        when:
        def result = runTasksWithConfigurationCacheAndCheck('checkConfigurationCacheLock', '-Penv=remote')

        then:
        result.tasks(TaskOutcome.SUCCESS)*.path.contains(':checkConfigurationCacheLock')
    }

    def 'checkConfigurationCacheLock --fix populates the allow list lock'() {
        given:
        def tasks = '''
            :checkConfigurationCacheLock
            :classes
            :compileJava
            :processResources
        '''.stripIndent(true)

        file('gradle/configuration-cache-allowed-tasks') << '''
            classes
            checkConfigurationCacheLock
        '''.stripIndent(true)
        file('gradle/configuration-cache-allowed-tasks.lock') << ''

        when:
        def result = runTasksWithConfigurationCache(true, false,'checkConfigurationCacheLock', '--fix')

        then:
        result.tasks(TaskOutcome.SUCCESS)*.path.contains(':checkConfigurationCacheLock')
        file('gradle/configuration-cache-allowed-tasks.lock').text.trim() == tasks.trim()
    }

    def 'checkConfigurationCacheLock with bad input fails'() {
        given:
        def tasks = '''
            :fakeTask
            :checkConfigurationCacheLock
        '''.stripIndent(true)

        file('gradle/configuration-cache-allowed-tasks') << tasks
        file('gradle/configuration-cache-allowed-tasks.lock') << tasks

        when:
        def result = runTasksAndFailWithConfigurationCache('checkConfigurationCacheLock', '--fix')

        then:
        result.tasks(TaskOutcome.FAILED)*.path.contains(':checkConfigurationCacheLock')
    }

    def 'fails when lock file has both missing and extra tasks'() {
        given:
        file('gradle/configuration-cache-allowed-tasks') << '''
            classes
            checkConfigurationCacheLock
            :jar
        '''.stripIndent(true)

        // Lock file has different set of tasks
        file('gradle/configuration-cache-allowed-tasks.lock') << '''
            :compileJava
            :compileTestJava
            :test
            :checkConfigurationCacheLock
        '''.stripIndent(true)

        when:
        def result = runTasksAndFailWithConfigurationCache('checkConfigurationCacheLock')

        then:
        result.tasks(TaskOutcome.FAILED)*.path.contains(':checkConfigurationCacheLock')
        result.output.contains('Lock file does not match the tasks that would run')
        result.output.contains('Total tasks in lock file: 4')
        result.output.contains('Total tasks that would execute: 5')

        then:
        runTasksWithConfigurationCache('checkConfigurationCacheLock', '--fix')
        file('gradle/configuration-cache-allowed-tasks.lock').text.trim() == '''
            :checkConfigurationCacheLock
            :classes
            :compileJava
            :jar
            :processResources
        '''.stripIndent(true).trim()
    }

    def 'checkConfigurationCacheLock fails when sub-project added then fix works'() {
        given:
        def tasks = '''
            :classes
            :compileJava
            :checkConfigurationCacheLock
            :processResources
        '''.stripIndent(true)

        file('gradle/configuration-cache-allowed-tasks') << '''
            classes
            checkConfigurationCacheLock
        '''.stripIndent(true)
        file('gradle/configuration-cache-allowed-tasks.lock') << tasks

        addSubproject("subproject")
        //language=gradle
        file('subproject/build.gradle') << '''
            apply plugin: 'java-library'
        '''.stripIndent(true)

        when:
        def result = runTasksAndFailWithConfigurationCache('checkConfigurationCacheLock')

        then:
        result.tasks(TaskOutcome.FAILED)*.path.contains(':checkConfigurationCacheLock')
        result.output.contains('Lock file does not match the tasks that would run')

        when:
        def fixResult = runTasksWithConfigurationCache('checkConfigurationCacheLock', '--fix')

        then:
        fixResult.tasks(TaskOutcome.SUCCESS)*.path.contains(':checkConfigurationCacheLock')
        def lockContent = file('gradle/configuration-cache-allowed-tasks.lock').text
        lockContent.contains(':classes')
        lockContent.contains(':subproject:classes')
        lockContent.contains(':subproject:compileJava')
    }
}
