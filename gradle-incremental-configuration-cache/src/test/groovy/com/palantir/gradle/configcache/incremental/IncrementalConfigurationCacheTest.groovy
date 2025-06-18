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
import java.nio.file.Files


class IncrementalConfigurationCacheTest extends IntegrationTestKitSpec {
    def setup() {
        definePluginOutsideOfPluginBlock = true
        keepFiles = true
    }

    def "blows up if allow list file does not exist"() {
        // language=gradle
        buildFile << """
            import com.palantir.gradle.configcache.incremental.IncrementalConfigurationCachePlugin

            apply plugin: 'com.palantir.incremental-configuration-cache'
            apply plugin: 'java-library'

            repositories {
                mavenCentral()
                mavenLocal()
            }
        """.stripIndent(true)

        expect:
        def buildResult = createRunner(['classes', '--configuration-cache'] as String[]).buildAndFail()
        assert buildResult.output.contains('Configuration cache allowed tasks file not found'),
                "Expected task to blow up"
    }

    def "blows up if applied to non root project"() {
        def subprojectDir = new File(getProjectDir(), 'subproject')
        subprojectDir.mkdirs()
        new File(subprojectDir, 'build.gradle') << """
            apply plugin: 'com.palantir.incremental-configuration-cache'
            apply plugin: 'java-library'
        """.stripIndent(true)

        def allowListFilePath = getProjectDir().toPath().resolve(IncrementalConfigurationCachePlugin.ALLOW_LIST_FILE)
        Files.createDirectories(allowListFilePath.getParent())
        Files.createFile(allowListFilePath) << """
        :compileJava
        :processResources
        :classes
        """

        // language=gradle
        settingsFile << """
        include 'subproject'
        """

        // language=gradle
        buildFile << """
            apply plugin: 'com.palantir.incremental-configuration-cache'
            apply plugin: 'java-library'

            repositories {
                mavenCentral()
                mavenLocal()
            }
        """.stripIndent(true)

        expect:
        def buildResult = createRunner(['classes', '--configuration-cache'] as String[]).buildAndFail()
        assert buildResult.output.contains('Must be applied only to root project'),
                "Expected build to fail when plugin is applied to a non-root project"
    }


    def "tasks in allow list run with config cache"() {
        def allowListFilePath = getProjectDir().toPath().resolve(IncrementalConfigurationCachePlugin.ALLOW_LIST_FILE)
        Files.createDirectories(allowListFilePath.getParent())
        Files.createFile(allowListFilePath) << """
        :compileJava
        :processResources
        :classes
        """

        // language=gradle
        buildFile << """
            import com.palantir.gradle.configcache.incremental.IncrementalConfigurationCachePlugin

            apply plugin: 'com.palantir.incremental-configuration-cache'
            apply plugin: 'java-library'

            repositories {
                mavenCentral()
                mavenLocal()
            }
        """.stripIndent(true)

        expect:
        assert runTasksWithConfigurationCache("classes")
    }

    def "tasks not in allow list don't run with config cache"() {
        def allowListFilePath = getProjectDir().toPath().resolve(IncrementalConfigurationCachePlugin.ALLOW_LIST_FILE)
        Files.createDirectories(allowListFilePath.getParent())
        def allowListFile = Files.createFile(allowListFilePath)
        // Doesn't contain :classes
        allowListFile << """
        :compileJava
        :processResources
        """

        // language=gradle
        buildFile << """
            import com.palantir.gradle.configcache.incremental.IncrementalConfigurationCachePlugin

            apply plugin: 'com.palantir.incremental-configuration-cache'
            apply plugin: 'java-library'

            repositories {
                mavenCentral()
                mavenLocal()
            }
        """.stripIndent(true)

        expect:
        assert !createRunner(['classes', '--configuration-cache'] as String[]).build().output.contains('Configuration cache entry stored.'),
                "Expected task to not run with configuration cache, but it did"
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