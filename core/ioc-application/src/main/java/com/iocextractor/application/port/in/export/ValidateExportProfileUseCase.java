package com.iocextractor.application.port.in.export;

/**
 * Validates an export command without activating storage or filesystem infrastructure.
 *
 * <p>Driving adapters use this lightweight preflight before resolving the complete
 * export graph. The export service repeats validation at its own boundary.
 */
@FunctionalInterface
public interface ValidateExportProfileUseCase {

    /**
     * Validates that the requested profile can be selected.
     *
     * @param command requested export
     * @throws IllegalArgumentException when the profile is unknown
     */
    void validate(ExportArtifactsCommand command);
}
