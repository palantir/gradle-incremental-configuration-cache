<p align="right">
<a href="https://autorelease.general.dmz.palantir.tech/palantir/gradle-incremental-configuration-cache"><img src="https://img.shields.io/badge/Perform%20an-Autorelease-success.svg" alt="Autorelease"></a>
</p>

# gradle-incremental-configuration-cache

This gradle plugin allows for the incremental adoption of the configuration cache.

## Usage
To apply the plugin:

```gradle
apply plugin: 'com.palantir.incremental-configuration-cache'
```


## Motivation

Rolling out Gradle's [Configuration Cache](https://docs.gradle.org/current/userguide/configuration_cache.html) is hard. If you turn on the Configuration Cache by default, builds start to fail, as the plugins they depend on haven't been [updated to support the Configuration Cache](https://docs.gradle.org/current/userguide/configuration_cache.html#config_cache:requirements). 

Annoyingly, warn mode [fails your builds if configuration cache problems are found](https://github.com/gradle/gradle/issues/25235). This deviates from the expected and desired behavior, which is to warn about configuration cache issues, but run the build without configuration cache successfully.

This plugin enables incrementally rolling out the configuration cache, one task at a time. It reads `gradle/configuration-cache-allowed-tasks` for fully qualified names of tasks you want to run with the configuration cache, and turns off configuration caching for all other tasks in the project. This allows you to turn on running configuration cache by default, while incrementally fixing tasks and adding them to  `gradle/configuration-cache-allowed-tasks`. 

This plugin also prevents regressions — people adding configuration cache issues to tasks that already support them.


## Configuration

The only external dependency is the plain text file `gradle/configuration-cache-allowed-tasks` at the root of your project.  