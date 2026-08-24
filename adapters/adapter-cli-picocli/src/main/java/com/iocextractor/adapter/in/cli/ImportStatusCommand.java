package com.iocextractor.adapter.in.cli;

import com.iocextractor.application.dataframeimport.model.ImportDeliveryState;
import com.iocextractor.application.port.in.dataframeimport.QueryDataframeImportStatusUseCase;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

import java.util.concurrent.Callable;

/** Safe aggregate ledger status without source object or IOC detail. */
@Component
@Command(name = "status", mixinStandardHelpOptions = true,
        description = "Show aggregate managed dataframe import status.")
public final class ImportStatusCommand implements Callable<Integer> {

    private final ObjectProvider<QueryDataframeImportStatusUseCase> statusUseCases;

    @Spec
    private CommandSpec spec;

    /** Creates a metadata-only command shell. */
    public ImportStatusCommand() {
        this(null);
    }

    @Autowired
    public ImportStatusCommand(ObjectProvider<QueryDataframeImportStatusUseCase> statusUseCases) {
        this.statusUseCases = statusUseCases;
    }

    @Override
    public Integer call() {
        if (statusUseCases == null || statusUseCases.getIfAvailable() == null) {
            throw new IllegalStateException("Managed dataframe import status is not enabled");
        }
        var status = statusUseCases.getObject().status();
        spec.commandLine().getOut().printf("Recovery complete: %s%n", status.recoveryComplete());
        for (ImportDeliveryState state : ImportDeliveryState.values()) {
            long count = status.stateCounts().getOrDefault(state, 0L);
            if (count > 0) {
                spec.commandLine().getOut().printf("  %-20s %d%n", state, count);
            }
        }
        status.headSequence().ifPresent(sequence ->
                spec.commandLine().getOut().printf("Head sequence: %d%n", sequence.value()));
        status.headState().ifPresent(state ->
                spec.commandLine().getOut().printf("Head state: %s%n", state));
        status.headAge().ifPresent(age ->
                spec.commandLine().getOut().printf("Head age: %ds%n", age.toSeconds()));
        if (status.headRetryCount() > 0) {
            spec.commandLine().getOut().printf("Head retry count: %d%n", status.headRetryCount());
        }
        status.headRetryDelay().ifPresent(delay ->
                spec.commandLine().getOut().printf("Head retry delay: %ds%n", delay.toSeconds()));
        status.headCode().ifPresent(code ->
                spec.commandLine().getOut().printf("Head code: %s%n", code));
        return 0;
    }
}
