package com.iocextractor.application.observation;

import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.port.in.ExtractIocsUseCase;
import com.iocextractor.application.port.in.ExtractionCommand;
import com.iocextractor.application.port.in.ExtractionResult;
import com.iocextractor.application.port.out.observation.ObservationRegistrationStore;

import java.util.Objects;

/** Oneshot admission decorator; dry-run remains free of durable side effects. */
public final class ObservationOrderedExtractionDecorator implements ExtractIocsUseCase {

    private final ExtractIocsUseCase delegate;
    private final ObservationRegistrationStore registrations;

    public ObservationOrderedExtractionDecorator(ExtractIocsUseCase delegate,
                                                  ObservationRegistrationStore registrations) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.registrations = Objects.requireNonNull(registrations, "registrations");
    }

    @Override
    public ExtractionResult extract(ExtractionCommand command) {
        Objects.requireNonNull(command, "command");
        if (command.dryRun()) {
            return delegate.extract(command);
        }
        ObservationId observationId = new ObservationId(command.runId());
        RegisteredObservation registration = registrations.registerNew(
                observationId, ObservationOrigin.ONESHOT);
        ExtractionCommand ordered = new ExtractionCommand(command.runId(), command.source(), false,
                command.lifecycleWriteContext(), registration);
        try {
            ExtractionResult result = delegate.extract(ordered);
            registrations.markTerminal(observationId, registration.namespaceId());
            return result;
        } catch (RuntimeException failure) {
            try {
                registrations.markTerminal(observationId, registration.namespaceId());
            } catch (RuntimeException finalizationFailure) {
                failure.addSuppressed(finalizationFailure);
            }
            throw failure;
        }
    }
}
