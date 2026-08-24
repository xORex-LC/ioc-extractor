package com.iocextractor.adapter.in.cli;

import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.port.in.dataframeimport.ReplayDataframeImportCommand;
import com.iocextractor.application.port.in.dataframeimport.ReplayDataframeImportUseCase;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.util.UUID;
import java.util.concurrent.Callable;

/** Creates a causally linked occurrence without reopening terminal state. */
@Component
@Command(name = "replay", mixinStandardHelpOptions = true,
        description = "Replay a retained terminal delivery as a new occurrence.")
public final class ImportReplayCommand implements Callable<Integer> {

    private final ObjectProvider<ReplayDataframeImportUseCase> replayUseCases;

    @Spec
    private CommandSpec spec;

    @Option(names = "--delivery", required = true, description = "Terminal delivery ID.")
    private String delivery;

    @Option(names = "--new-delivery", description = "Optional new occurrence ID.")
    private String newDelivery;

    /** Creates a metadata-only command shell. */
    public ImportReplayCommand() {
        this(null);
    }

    public ImportReplayCommand(ObjectProvider<ReplayDataframeImportUseCase> replayUseCases) {
        this.replayUseCases = replayUseCases;
    }

    @Override
    public Integer call() {
        if (replayUseCases == null || replayUseCases.getIfAvailable() == null) {
            throw new IllegalStateException("Managed dataframe import replay is not enabled");
        }
        ImportDeliveryId replayId = new ImportDeliveryId(
                newDelivery == null || newDelivery.isBlank()
                        ? UUID.randomUUID().toString() : newDelivery);
        var result = replayUseCases.getObject().replay(new ReplayDataframeImportCommand(
                new ImportDeliveryId(delivery), replayId));
        spec.commandLine().getOut().printf("Replay delivery: id=%s sequence=%d state=%s%n",
                result.delivery().id().value(), result.delivery().sequence().value(),
                result.delivery().state());
        return 0;
    }
}
