package com.palantir.gradle.configcache.incremental;

import com.google.common.collect.ImmutableList;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.tooling.GradleConnectionException;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;

public abstract class DryRunTask extends DefaultTask {

    @Input
    public abstract SetProperty<String> getTasksToDryRun();

    @Input
    public abstract ListProperty<String> getArguments();

    @Input
    @org.gradle.api.tasks.Optional
    public abstract Property<String> getInitScript();

    @Inject
    protected abstract ProjectLayout getProjectLayout();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    private Path dryRunResultFile() {
        return getOutputDirectory().get().file("dryRunResult").getAsFile().toPath();
    }

    private Path dryRunErrorFile() {
        return getOutputDirectory().get().file("dryRunError").getAsFile().toPath();
    }

    @TaskAction
    public final void dryRun() throws IOException {
        Set<String> tasks = getTasksToDryRun().get();

        if (tasks.isEmpty()) {
            getLogger().info("No tasks to dry-run");
            return;
        }

        getLogger().info("Dry-running {} tasks", tasks.size());

        GradleConnector connector = GradleConnector.newConnector()
                .forProjectDirectory(getProjectLayout().getProjectDirectory().getAsFile());

        try (ProjectConnection connection = connector.connect()) {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            try {
                connection
                        .newBuild()
                        .withArguments(buildArguments())
                        .forTasks(tasks.toArray(new String[0]))
                        .setStandardOutput(outputStream)
                        .setStandardError(outputStream)
                        .run();

                getLogger().info("All {} tasks dry-ran successfully", tasks.size());

                // If we have successfully ran dry run write the dry-ran tasks
                TaskListFile.write(
                        dryRunResultFile(), parseDryRunResult(outputStream.toString(StandardCharsets.UTF_8)));
            } catch (GradleConnectionException e) {
                getLogger().info("Failed to run Dry-run tasks", e);
                // If we failed to run write the output
                Files.writeString(dryRunErrorFile(), outputStream.toString(StandardCharsets.UTF_8));
            }
        }
    }

    private ImmutableList<String> buildArguments() {
        ImmutableList.Builder<String> argumentsBuilder = ImmutableList.builder();
        argumentsBuilder.add("--dry-run");
        argumentsBuilder.addAll(getArguments().get());

        // GradleConnector runs builds in a separate process, so we must explicitly pass init scripts.
        // This is required for Nebula tests, which rely on init scripts for test setup.
        if (getInitScript().isPresent() && !getInitScript().get().isBlank()) {
            argumentsBuilder.add("--init-script=" + getInitScript().get());
        }

        return argumentsBuilder.build();
    }

    private Set<String> parseDryRunResult(String output) {
        return Pattern.compile("(:[\\w:-]+)\\s+SKIPPED")
                .matcher(output)
                .results()
                .map(m -> m.group(1))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    public final Set<String> dryRunResult() {
        return new TaskListFile(dryRunResultFile()).loadTasks();
    }

    public final Optional<String> dryRunError() throws IOException {
        if (Files.notExists(dryRunErrorFile())) {
            return Optional.empty();
        }
        return Optional.of(Files.readString(dryRunErrorFile(), StandardCharsets.UTF_8)
                        .trim())
                .filter(s -> !s.isEmpty());
    }
}
