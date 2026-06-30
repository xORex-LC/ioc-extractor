package com.iocextractor.adapter.in.cli;

import com.iocextractor.application.port.in.sync.ArtifactPublishCommand;
import com.iocextractor.application.port.in.sync.ArtifactPublishResult;
import com.iocextractor.application.port.in.sync.ArtifactPublishUseCase;
import com.iocextractor.application.port.in.sync.RemoteFetchCommand;
import com.iocextractor.application.port.in.sync.RemoteFetchResult;
import com.iocextractor.application.port.in.sync.RemoteFetchUseCase;
import com.iocextractor.application.port.in.sync.ValidateSyncSelectionUseCase;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.util.Optional;
import java.util.concurrent.Callable;

/** Explicit fetch-then-publish operator command with full preflight before any I/O. */
@Component
@Command(name = "all", mixinStandardHelpOptions = true,
        description = "Run remote fetch, then publish completed export slices.")
public final class SyncAllCommand implements Callable<Integer> {

    private final ValidateSyncSelectionUseCase validator;
    private final ObjectProvider<RemoteFetchUseCase> fetchUseCase;
    private final ObjectProvider<ArtifactPublishUseCase> publishUseCase;

    @Spec
    private CommandSpec spec;

    @Option(names = "--source", description = "Configured fetch source name.")
    private String source;

    @Option(names = "--profile", description = "Configured export profile name.")
    private String profile;

    @Option(names = "--target", description = "Configured publish target name.")
    private String target;

    @Option(names = "--endpoint", description = "Endpoint filter applied to both operations.")
    private String endpoint;

    @Option(names = "--dry-run", description = "Inspect both operations without durable writes.")
    private boolean dryRun;

    /**
     * Creates a combined command that preflights both operations before resolving either graph.
     *
     * @param validator configuration-backed selection preflight
     * @param fetchUseCase lazily resolved remote fetch primary port
     * @param publishUseCase lazily resolved artifact publish primary port
     */
    public SyncAllCommand(ValidateSyncSelectionUseCase validator,
                          ObjectProvider<RemoteFetchUseCase> fetchUseCase,
                          ObjectProvider<ArtifactPublishUseCase> publishUseCase) {
        this.validator = validator;
        this.fetchUseCase = fetchUseCase;
        this.publishUseCase = publishUseCase;
    }

    @Override
    public Integer call() {
        Optional<String> endpointFilter = optional(endpoint);
        RemoteFetchCommand fetchCommand = new RemoteFetchCommand(optional(source), endpointFilter, dryRun);
        ArtifactPublishCommand publishCommand = new ArtifactPublishCommand(
                optional(profile), optional(target), endpointFilter, dryRun);

        validator.validateFetch(fetchCommand);
        validator.validatePublish(publishCommand);
        RemoteFetchUseCase fetcher = requireFetchUseCase();
        ArtifactPublishUseCase publisher = requirePublishUseCase();

        RemoteFetchResult fetchResult = fetcher.fetch(fetchCommand);
        ArtifactPublishResult publishResult = publishCommand.dryRun()
                ? publisher.reconcile(publishCommand)
                : publishAfterReconcile(publisher, publishCommand);
        render(fetchResult, publishResult);
        return fetchResult.failed() == 0 && publishResult.failed() == 0 ? 0 : 1;
    }

    private ArtifactPublishResult publishAfterReconcile(ArtifactPublishUseCase publisher,
                                                        ArtifactPublishCommand command) {
        publisher.reconcile(command);
        return publisher.publish(command);
    }

    private Optional<String> optional(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private void render(RemoteFetchResult fetch, ArtifactPublishResult publish) {
        spec.commandLine().getOut().printf("Fetch%s: fetched=%d skipped=%d failed=%d%n",
                dryRun ? " dry-run" : "", fetch.fetched(), fetch.skipped(), fetch.failed());
        spec.commandLine().getOut().printf(
                "Publish%s: pending=%d succeeded=%d failed=%d abandoned=%d%n",
                dryRun ? " dry-run" : "", publish.pending(), publish.succeeded(),
                publish.failed(), publish.abandoned());
    }

    private RemoteFetchUseCase requireFetchUseCase() {
        RemoteFetchUseCase fetcher = fetchUseCase.getIfAvailable();
        if (fetcher == null) {
            throw unavailable("fetch");
        }
        return fetcher;
    }

    private ArtifactPublishUseCase requirePublishUseCase() {
        ArtifactPublishUseCase publisher = publishUseCase.getIfAvailable();
        if (publisher == null) {
            throw unavailable("publish");
        }
        return publisher;
    }

    private IllegalStateException unavailable(String operation) {
        return new IllegalStateException(
                "Remote sync " + operation + " requires enabled JDBC-backed sync configuration");
    }
}
