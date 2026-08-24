package com.iocextractor.application.dataframeimport;

import com.iocextractor.application.artifact.CanonicalArtifactsChanged;
import com.iocextractor.application.artifact.lifecycle.CanonicalDeadlineScheduleChanged;
import com.iocextractor.application.artifact.lifecycle.MutableArtifactProjectionRequired;
import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.artifact.lifecycle.ProjectionGeneration;
import com.iocextractor.application.dataframeimport.model.ImportPromotionOutcome;
import com.iocextractor.application.port.out.dataframeimport.CanonicalImportCommand;
import com.iocextractor.application.port.out.dataframeimport.CanonicalImportResult;
import com.iocextractor.application.port.out.dataframeimport.CanonicalImportWriter;
import com.iocextractor.platform.events.ControlEvent;
import com.iocextractor.platform.events.ControlEventPublisher;

import java.util.ArrayList;
import java.util.Objects;

/** Publishes only lossy post-commit convergence hints around durable import state. */
public final class EventPublishingCanonicalImportWriter implements CanonicalImportWriter {

    private final CanonicalImportWriter delegate;
    private final ControlEventPublisher events;

    /** Creates a post-commit decorator; event failure never changes commit outcome. */
    public EventPublishingCanonicalImportWriter(
            CanonicalImportWriter delegate,
            ControlEventPublisher events) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override
    public CanonicalImportResult promote(CanonicalImportCommand command) {
        CanonicalImportResult result = delegate.promote(command);
        if (result.outcome() == ImportPromotionOutcome.ALREADY_COMMITTED) {
            return result;
        }
        String operation = "import-" + command.deliveryId().value();
        ObservationId observation = new ObservationId("import:" + command.deliveryId().value());
        for (String artifact : result.observedArtifacts()) {
            publish(CanonicalDeadlineScheduleChanged.from(
                    observation, artifact, result.effectiveAt()));
        }
        for (var entry : result.projectionGenerations().entrySet()) {
            publish(MutableArtifactProjectionRequired.from(
                    operation, entry.getKey(), new ProjectionGeneration(entry.getValue()),
                    result.effectiveAt()));
        }
        if (!result.affectedArtifacts().isEmpty()) {
            publish(CanonicalArtifactsChanged.from(
                    operation, new ArrayList<>(result.affectedArtifacts()), result.effectiveAt()));
        }
        return result;
    }

    private void publish(ControlEvent event) {
        try {
            events.publish(event);
        } catch (RuntimeException ignored) {
            // Dataframe receipt, deadline and projection generations are durable backstops.
        }
    }
}
