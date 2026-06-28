package com.iocextractor.adapter.in.cli;

import com.iocextractor.application.export.ExportRunStatus;
import com.iocextractor.application.port.in.export.ExportArtifactsCommand;
import com.iocextractor.application.port.in.export.ExportArtifactsResult;
import com.iocextractor.application.port.in.export.ExportArtifactsUseCase;
import com.iocextractor.application.port.in.export.ValidateExportProfileUseCase;
import com.iocextractor.observability.EventAction;
import com.iocextractor.observability.EventOutcome;
import com.iocextractor.observability.LogField;
import com.iocextractor.observability.logging.LogEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/** Driving adapter for on-demand immutable artifact export. */
@Component
@Command(
        name = "export",
        mixinStandardHelpOptions = true,
        description = "Export one configured artifact profile as an immutable local slice.")
public final class ExportCommand implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(ExportCommand.class);

    private final ValidateExportProfileUseCase profileValidator;
    private final ObjectProvider<ExportArtifactsUseCase> useCase;
    private final String observabilityMode;

    @Option(names = "--profile", required = true, description = "Configured export profile name.")
    private String profile;

    /**
     * Creates a lazy command that does not resolve service storage before {@link #call()}.
     *
     * @param profileValidator IO-free profile preflight
     * @param useCase lazy artifact export primary port
     * @param observabilityMode operational logging mode
     */
    public ExportCommand(ValidateExportProfileUseCase profileValidator,
                         ObjectProvider<ExportArtifactsUseCase> useCase,
                         @Value("${ioc.observability.mode:oneshot}") String observabilityMode) {
        this.profileValidator = profileValidator;
        this.useCase = useCase;
        this.observabilityMode = observabilityMode;
    }

    @Override
    public Integer call() {
        LogEvents.info(log)
                .action(EventAction.COMMAND_START)
                .outcome(EventOutcome.UNKNOWN)
                .field(LogField.IOC_MODE, observabilityMode)
                .field(LogField.IOC_EXPORT_PROFILE, profile)
                .message("export command started")
                .log();
        try {
            ExportArtifactsCommand command = new ExportArtifactsCommand(profile);
            profileValidator.validate(command);
            ExportArtifactsUseCase exporter = useCase.getIfAvailable();
            if (exporter == null) {
                throw new IllegalStateException("Artifact export requires JDBC service and dataframe storage");
            }
            ExportArtifactsResult result = exporter.export(command);
            render(result);
            LogEvents.info(log)
                    .action(EventAction.COMMAND_COMPLETE)
                    .outcome(EventOutcome.SUCCESS)
                    .field(LogField.IOC_MODE, observabilityMode)
                    .field(LogField.IOC_EXPORT_PROFILE, profile)
                    .field(LogField.IOC_EXPORT_SLICE_ID, result.runId())
                    .message("export command completed")
                    .log();
            return 0;
        } catch (RuntimeException failure) {
            LogEvents.error(log)
                    .action(EventAction.COMMAND_COMPLETE)
                    .outcome(EventOutcome.FAILURE)
                    .field(LogField.IOC_MODE, observabilityMode)
                    .field(LogField.IOC_EXPORT_PROFILE, profile)
                    .message("export command failed")
                    .log(failure);
            throw failure;
        }
    }

    private void render(ExportArtifactsResult result) {
        if (result.status() == ExportRunStatus.SKIPPED && result.runId() == null) {
            System.out.printf("Profile '%s': no canonical or plan changes; export skipped%n", result.profile());
            return;
        }
        System.out.printf("Profile '%s': %s (run=%s, slice=%s)%n",
                result.profile(), result.status(), result.runId(), result.sliceName());
    }
}
