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

class IncrementalConfigurationCacheTest extends IntegrationTestKitSpec {
    def setup() {
        definePluginOutsideOfPluginBlock = true
        keepFiles = true
    }

    def "does nothing if allowlist file does not exist"() {
        // Run the main build
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
        assert runAndAssertNoConfigurationCache("classes")
    }

    def "tasks in allowlist run with config cache"() {
        // Save the task graph of :classes to the allow list file
        // language=gradle
        buildFile << """
        import com.palantir.gradle.configcache.incremental.IncrementalConfigurationCachePlugin
        import java.nio.file.Files

        apply plugin: 'java-library'
        
        def allowListFilePath = getProjectDir().toPath().resolve(IncrementalConfigurationCachePlugin.ALLOW_LIST_FILE)
        Files.createDirectories(allowListFilePath.getParent())
        def allowListFile = file(allowListFilePath)
        allowListFile.createNewFile()
        println("File created at: " + allowListFilePath)

        gradle.taskGraph.whenReady { graph ->
            graph.getAllTasks().each { task ->
                 allowListFile << task.path
                 allowListFile << '\\n'
            }
        }
        """
        assert createRunner("classes").build().output.contains("BUILD SUCCESSFUL")
        buildFile.text = ''

        // Run the main build
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

    def "tasks not in allowlist don't run with config cache"() {
        // Create an allowlist that does not contain the :classes task
        // language=gradle
        buildFile << """
        import com.palantir.gradle.configcache.incremental.IncrementalConfigurationCachePlugin
        import java.nio.file.Files

        apply plugin: 'java-library'
        
        def allowListFilePath = getProjectDir().toPath().resolve(IncrementalConfigurationCachePlugin.ALLOW_LIST_FILE)
        Files.createDirectories(allowListFilePath.getParent())
        def allowListFile = file(allowListFilePath)
        allowListFile.createNewFile()
        println("File created at: " + allowListFilePath)
        
        allowListFile << ':processResources'
        allowListFile << '\\n'
        """
        def createAllowList = createRunner("help").build()
        assert createAllowList.output.contains("BUILD SUCCESSFUL")

        buildFile.text = ''

        // Run the main build
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
        assert runAndAssertNoConfigurationCache("classes")
    }

    private boolean runAndAssertNoConfigurationCache(String... tasks) {
        def run = createRunner(tasks + ['--configuration-cache'] as String[]).build()
        assert run.output.contains("Calculating task graph as no cached configuration is available for tasks: classes"),
                "Expected first run to store configuration cache, but output was: ${run.output}"

        return true
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