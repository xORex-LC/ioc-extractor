package com.iocextractor.adapter.in.cli;

import com.iocextractor.application.port.in.sync.ArtifactPublishCommand;
import com.iocextractor.application.port.in.sync.ArtifactPublishExecutionResult;
import com.iocextractor.application.port.in.sync.ArtifactPublishResult;
import com.iocextractor.application.port.in.sync.ArtifactPublishUseCase;
import com.iocextractor.application.port.in.sync.ValidateSyncSelectionUseCase;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.util.Optional;
import java.util.concurrent.Callable;

/** Driving CLI adapter for one explicit completed-slice publish cycle. */
@Component
@Command(name = "publish", mixinStandardHelpOptions = true,
        description = "Publish verified completed export slices to configured targets.")
public final class SyncPublishCommand implements Callable<Integer> {

    private final ValidateSyncSelectionUseCase validator;
    private final ObjectProvider<ArtifactPublishUseCase> useCase;

    @Spec
    private CommandSpec spec;

    @Option(names = "--profile", description = "Configured export profile name.")
    private String profile;

    @Option(names = "--target", description = "Configured publish target name.")
    private String target;

    @Option(names = "--endpoint", description = "Configured endpoint name.")
    private String endpoint;

    @Option(names = "--dry-run", description = "Inspect work without remote or ledger writes.")
    private boolean dryRun;

    /** Creates a metadata-only command instance for Picocli model construction. */
    public SyncPublishCommand() {
        this(null, null);
    }

    /**
     * Creates a command with IO-free validation and lazy use-case resolution.
     *
     * @param validator configuration-backed selection preflight
     * @param useCase lazily resolved artifact publish primary port
     */
    public SyncPublishCommand(ValidateSyncSelectionUseCase validator,
                              ObjectProvider<ArtifactPublishUseCase> useCase) {
        this.validator = validator;
        this.useCase = useCase;
    }

    @Override
    public Integer call() {
        ArtifactPublishCommand command = command();
        requireValidator().validatePublish(command);
        ArtifactPublishUseCase publisher = requireUseCase();
        if (command.dryRun()) {
            ArtifactPublishResult summary = publisher.reconcile(command);
            renderSummary(summary);
            return summary.failed() == 0 ? 0 : 1;
        }
        publisher.reconcile(command);
        ArtifactPublishExecutionResult result = publisher.publish(command);
        renderExecution(result);
        return result.failed() == 0 ? 0 : 1;
    }

    ArtifactPublishCommand command() {
        return new ArtifactPublishCommand(
                optional(profile), optional(target), optional(endpoint), dryRun);
    }

    private ValidateSyncSelectionUseCase requireValidator() {
        if (validator == null) {
            throw unavailable();
        }
        return validator;
    }

    private Optional<String> optional(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    void renderSummary(ArtifactPublishResult result) {
        spec.commandLine().getOut().printf(
                "Publish%s: pending=%d succeeded=%d failed=%d abandoned=%d%n",
                dryRun ? " dry-run" : "", result.pending(), result.succeeded(),
                result.failed(), result.abandoned());
    }

    void renderExecution(ArtifactPublishExecutionResult result) {
        spec.commandLine().getOut().printf(
                "Publish: attempted=%d succeeded=%d recovered=%d failed=%d%n",
                result.attempted(), result.succeeded(), result.recovered(), result.failed());
    }

    private ArtifactPublishUseCase requireUseCase() {
        if (useCase == null) {
            throw unavailable();
        }
        ArtifactPublishUseCase publisher = useCase.getIfAvailable();
        if (publisher == null) {
            throw unavailable();
        }
        return publisher;
    }

    private IllegalStateException unavailable() {
        return new IllegalStateException(
                "Remote sync publish requires enabled JDBC-backed sync configuration");
    }
}
