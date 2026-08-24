package com.iocextractor.adapter.in.cli;

import com.iocextractor.application.dataframeimport.model.ImportSourceId;
import com.iocextractor.application.port.in.dataframeimport.ValidateDataframeImportCommand;
import com.iocextractor.application.port.in.dataframeimport.ValidateDataframeImportUseCase;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/** Side-effect-free structural and row-planning preview. */
@Component
@Command(name = "validate", mixinStandardHelpOptions = true,
        description = "Validate a CSV against one configured import source allowlist.")
public final class ImportValidateCommand implements Callable<Integer> {

    private final ObjectProvider<ValidateDataframeImportUseCase> validators;
    private final ObjectProvider<ImportPreviewFileLocator> locators;

    @Spec
    private CommandSpec spec;

    @Option(names = "--source", required = true, description = "Configured import source ID.")
    private String source;

    @Option(names = "--file", required = true, description = "CSV file to validate without importing.")
    private Path file;

    /** Creates a metadata-only command shell. */
    public ImportValidateCommand() {
        this(null, null);
    }

    @Autowired
    public ImportValidateCommand(ObjectProvider<ValidateDataframeImportUseCase> validators,
                                 ObjectProvider<ImportPreviewFileLocator> locators) {
        this.validators = validators;
        this.locators = locators;
    }

    @Override
    public Integer call() {
        ValidateDataframeImportUseCase validator = required(validators,
                "Managed dataframe import validation is not enabled");
        ImportPreviewFileLocator locator = required(locators,
                "Managed dataframe import preview is not enabled");
        var result = validator.validate(new ValidateDataframeImportCommand(
                new ImportSourceId(source), locator.reference(file)));
        spec.commandLine().getOut().printf(
                "Import preview: valid=%s sourceRows=%d accepted=%d rejected=%d%n",
                result.valid(), result.sourceRows(), result.acceptedRows(), result.rejectedRows());
        result.contractFingerprint().ifPresent(fingerprint ->
                spec.commandLine().getOut().printf("Contract fingerprint: %s%n", fingerprint.value()));
        if (!result.diagnosticCodes().isEmpty()) {
            spec.commandLine().getOut().printf("Diagnostic codes: %s%n",
                    String.join(",", result.diagnosticCodes()));
        }
        return result.valid() ? 0 : 3;
    }

    private <T> T required(ObjectProvider<T> provider, String message) {
        if (provider == null) {
            throw new IllegalStateException(message);
        }
        T value = provider.getIfAvailable();
        if (value == null) {
            throw new IllegalStateException(message);
        }
        return value;
    }
}
