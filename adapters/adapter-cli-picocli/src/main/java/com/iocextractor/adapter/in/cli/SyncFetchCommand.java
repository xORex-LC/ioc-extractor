package com.iocextractor.adapter.in.cli;

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

/** Driving CLI adapter for one explicit remote-to-inbox fetch cycle. */
@Component
@Command(name = "fetch", mixinStandardHelpOptions = true,
        description = "Fetch configured remote source files into the ingestion inbox.")
public final class SyncFetchCommand implements Callable<Integer> {

    private final ValidateSyncSelectionUseCase validator;
    private final ObjectProvider<RemoteFetchUseCase> useCase;

    @Spec
    private CommandSpec spec;

    @Option(names = "--source", description = "Configured fetch source name.")
    private String source;

    @Option(names = "--endpoint", description = "Configured endpoint name.")
    private String endpoint;

    @Option(names = "--dry-run", description = "List prospective work without inbox or ledger writes.")
    private boolean dryRun;

    /**
     * Creates a command with IO-free validation and lazy use-case resolution.
     *
     * @param validator configuration-backed selection preflight
     * @param useCase lazily resolved remote fetch primary port
     */
    public SyncFetchCommand(ValidateSyncSelectionUseCase validator,
                            ObjectProvider<RemoteFetchUseCase> useCase) {
        this.validator = validator;
        this.useCase = useCase;
    }

    @Override
    public Integer call() {
        RemoteFetchCommand command = command();
        validator.validateFetch(command);
        RemoteFetchUseCase fetcher = requireUseCase();
        RemoteFetchResult result = fetcher.fetch(command);
        render(result);
        return result.failed() == 0 ? 0 : 1;
    }

    RemoteFetchCommand command() {
        return new RemoteFetchCommand(optional(source), optional(endpoint), dryRun);
    }

    private Optional<String> optional(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    void render(RemoteFetchResult result) {
        spec.commandLine().getOut().printf("Fetch%s: fetched=%d skipped=%d failed=%d%n",
                dryRun ? " dry-run" : "", result.fetched(), result.skipped(), result.failed());
    }

    private RemoteFetchUseCase requireUseCase() {
        RemoteFetchUseCase fetcher = useCase.getIfAvailable();
        if (fetcher == null) {
            throw new IllegalStateException(
                    "Remote sync fetch requires enabled JDBC-backed sync configuration");
        }
        return fetcher;
    }
}
