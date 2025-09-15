<p align="right">
<a href="https://autorelease.general.dmz.palantir.tech/palantir/gradle-incremental-configuration-cache"><img src="https://img.shields.io/badge/Perform%20an-Autorelease-success.svg" alt="Autorelease"></a>
</p>

# gradle-incremental-configuration-cache

This gradle plugin allows for the incremental adoption of the [Configuration Cache](https://docs.gradle.org/8.14.3/userguide/configuration_cache.html).

## Usage
To apply the plugin:

```gradle
apply plugin: 'com.palantir.incremental-configuration-cache'
```

## FAQ

#### After applying this plugin, I'm seeing "configuration cache problems found in this build". What's going on?

Suppose a task `T` hasn't been [updated to support the configuration cache](https://docs.gradle.org/8.14.3/userguide/configuration_cache.html#config_cache:requirements), and is not in `gradle/configuration-cache-allowed-tasks`. 

When you [run gradle with the configuration cache](https://docs.gradle.org/8.14.3/userguide/configuration_cache.html#config_cache:usage:enable) , `T` will run successfully without the configuration cache, but all the configuration cache problems within `T`'s implementation will be surfaced as a warning to the user. Gradle does not provide a safe way to suppress these warnings. 


## Motivation

Rolling out Gradle's Configuration Cache is hard. If you turn on the Configuration Cache by default, builds start to fail, as the plugins they depend on haven't been [updated to support the Configuration Cache](https://docs.gradle.org/8.14.3/userguide/configuration_cache.html#config_cache:requirements).

Annoyingly, warn mode [fails your builds if Configuration Cache problems are found](https://github.com/gradle/gradle/issues/25235). This deviates from the expected and desired behavior, which is to warn about Configuration Cache issues, but run the build without Configuration Cache successfully.

This plugin enables incrementally rolling out the Configuration Cache, one task at a time. It reads `gradle/configuration-cache-allowed-tasks` for fully qualified names of tasks you want to run with the Configuration Cache, and turns off configuration caching for all other tasks in the project. This allows you to turn on running Configuration Cache by default, while incrementally fixing tasks and adding them to  `gradle/configuration-cache-allowed-tasks`.

This plugin also prevents regressions — people adding Configuration Cache issues to tasks that already support them.


## Limitations

The plugin only disables Configuration Cache for tasks. If your configuration phase is not compatible with the cache, you must resolve those issues first.

## Configuration

This plugin uses a two-file system to manage which tasks run with configuration cache enabled:

### `gradle/configuration-cache-allowed-tasks`
A plain text file containing high-level task names you want to enable for configuration cache. For example:
```
classes
test
jar
```

### `gradle/configuration-cache-allowed-tasks.lock`
An auto-generated lock file containing the exact task paths that will run when executing the tasks from the allowed-tasks file. This file is automatically maintained by the `checkConfigurationCacheLock` task.

### Managing the lock file

The plugin provides a `checkConfigurationCacheLock` task that:
- **Validates** the lock file matches what would actually execute (runs automatically as part of `check`)
- **Updates** the lock file when run with `--fix` flag

#### Updating after project changes
When you add subprojects, rename tasks, or modify task dependencies:
```bash
# Update the lock file to match the new project structure
./gradlew checkConfigurationCacheLock --fix
```

#### CI validation
The `checkConfigurationCacheLock` task is automatically added to the `check` lifecycle task, ensuring your lock file stays up-to-date:
```bash
./gradlew check  # Fails if lock file is out of date
```

The `validateConfigurationCacheEnabledTasks` task is automatically added to the `check` lifecycle task, ensuring all tasks in the allow list are configuration cache compatible. We chose to not run it locally to preserve local build performance. To run an task from the allow list locally with validation:
```bash
./gradlew <task> -Pconfiguration-cache-validation-mode
```
