package com.iocextractor.application.pipeline.stage;

import com.iocextractor.application.artifact.ArtifactWritePlan;
import com.iocextractor.application.pipeline.payload.ArtifactWriteSummary;
import com.iocextractor.application.pipeline.payload.PreparedArtifacts;
import com.iocextractor.application.pipeline.PipelineMetaAttributes;
import com.iocextractor.application.port.out.artifact.ArtifactProjection;
import com.iocextractor.application.port.out.artifact.ArtifactProjectionCommand;
import com.iocextractor.application.port.out.artifact.CanonicalArtifactRepository;
import com.iocextractor.application.artifact.lifecycle.CanonicalArtifactConfirmation;
import com.iocextractor.application.artifact.lifecycle.CanonicalRecordConfirmation;
import com.iocextractor.application.artifact.lifecycle.LifecycleWriteContext;
import com.iocextractor.application.observation.RegisteredObservation;
import com.iocextractor.application.port.out.artifact.ArtifactIdentityResolver;
import com.iocextractor.application.port.out.artifact.lifecycle.CanonicalArtifactWriter;
import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.DiagnosticException;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.codes.SinkDiagnosticCodes;
import com.iocextractor.platform.etl.Envelope;
import com.iocextractor.platform.etl.Stage;
import com.iocextractor.platform.etl.StageId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Writes retained indicators to configured sinks unless dry-run is enabled.
 */
public final class WriteArtifactsStage implements Stage<PreparedArtifacts, ArtifactWriteSummary> {

    private final CanonicalArtifactRepository repository;
    private final CanonicalArtifactWriter lifecycleWriter;
    private final ArtifactIdentityResolver identityResolver;
    private final ArtifactProjection projection;
    private final DiagnosticFactory diagnosticFactory;

    /**
     * Creates the stage.
     *
     * @param repository canonical storage port
     * @param projection post-commit projection port
     * @param diagnosticFactory factory for typed run failures
     */
    public WriteArtifactsStage(CanonicalArtifactRepository repository,
                               ArtifactProjection projection,
                               DiagnosticFactory diagnosticFactory) {
        this(repository, null, null, projection, diagnosticFactory);
    }

    /** Creates a stage that can select legacy or lifecycle-aware persistence per command. */
    public WriteArtifactsStage(CanonicalArtifactRepository repository,
                               CanonicalArtifactWriter lifecycleWriter,
                               ArtifactIdentityResolver identityResolver,
                               ArtifactProjection projection,
                               DiagnosticFactory diagnosticFactory) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.lifecycleWriter = lifecycleWriter;
        this.identityResolver = identityResolver;
        this.projection = Objects.requireNonNull(projection, "projection");
        this.diagnosticFactory = Objects.requireNonNull(diagnosticFactory, "diagnosticFactory");
    }

    @Override
    public StageId name() {
        return StageNames.WRITE_ARTIFACTS;
    }

    @Override
    public Envelope<ArtifactWriteSummary> process(Envelope<PreparedArtifacts> input) {
        var payload = input.payload();
        WriteExecution execution = input.meta().booleanAttribute(PipelineMetaAttributes.DRY_RUN, false)
                ? WriteExecution.empty()
                : execute(input, payload);
        return input.withPayload(new ArtifactWriteSummary(
                payload.extracted(),
                payload.retained(),
                execution.written(),
                execution.changedArtifacts())).withDiagnostics(execution.diagnostics());
    }

    private WriteExecution execute(Envelope<PreparedArtifacts> input, PreparedArtifacts payload) {
        LifecycleWriteContext lifecycle = lifecycleContext(input);
        validateExpectedArtifactCount(lifecycle, payload.plans().size());
        RegisteredObservation registration = registration(input);
        var written = new LinkedHashMap<String, Integer>();
        var changedArtifacts = new LinkedHashSet<String>();
        var diagnostics = new ArrayList<Diagnostic>();
        for (var plan : payload.plans()) {
            WriteOutcome outcome = writeCanonical(plan, lifecycle, registration);
            diagnostics.addAll(project(input.meta().runId(), plan.artifactName()));
            written.put(plan.artifactName(), outcome.inserted());
            if (outcome.publicChanged()) {
                changedArtifacts.add(plan.artifactName());
            }
        }
        return new WriteExecution(written, changedArtifacts, diagnostics);
    }

    private void validateExpectedArtifactCount(LifecycleWriteContext lifecycle, int actualArtifacts) {
        if (lifecycle != null && lifecycle.receipt().expectedArtifacts() != actualArtifacts) {
            throw new IllegalArgumentException(
                    "Lifecycle receipt artifact count does not match prepared plans");
        }
    }

    private WriteOutcome writeCanonical(ArtifactWritePlan plan,
                                        LifecycleWriteContext lifecycle,
                                        RegisteredObservation registration) {
        try {
            return lifecycle == null
                    ? write(plan, registration)
                    : confirm(plan, lifecycle, registration);
        } catch (RuntimeException failure) {
            throw writeFailure("canonical", plan.artifactName(), failure);
        }
    }

    private List<Diagnostic> project(String runId, String artifactName) {
        try {
            return projection.project(new ArtifactProjectionCommand(runId, artifactName)).diagnostics();
        } catch (RuntimeException failure) {
            throw writeFailure("projection", artifactName, failure);
        }
    }

    private WriteOutcome write(ArtifactWritePlan plan,
                               RegisteredObservation registration) {
        var result = repository.write(plan.materializeCommand(registration));
        return new WriteOutcome(result.inserted(), result.publicRowsChanged() > 0);
    }

    private WriteOutcome confirm(ArtifactWritePlan plan,
                                 LifecycleWriteContext context,
                                 RegisteredObservation registration) {
        if (lifecycleWriter == null || identityResolver == null) {
            throw new IllegalStateException("Lifecycle-aware canonical writer is not configured");
        }
        var records = plan.rows().stream()
                .map(row -> new CanonicalRecordConfirmation(
                        identityResolver.keyOf(plan.artifactName(), row.template())
                                .orElseThrow(() -> new IllegalArgumentException(
                                        "Prepared row has no canonical identity: " + plan.artifactName())),
                        row))
                .toList();
        var result = lifecycleWriter.confirm(new CanonicalArtifactConfirmation(
                context.observationId(),
                context.sourceKey(),
                context.receipt(),
                plan.artifactName(),
                plan.header(),
                records,
                registration));
        return new WriteOutcome(result.publicRowsInserted(), result.publicRowsChanged() > 0);
    }

    private RegisteredObservation registration(Envelope<PreparedArtifacts> input) {
        Object value = input.meta().attributes().get(PipelineMetaAttributes.REGISTERED_OBSERVATION);
        if (value == null) {
            LifecycleWriteContext lifecycle = lifecycleContext(input);
            return lifecycle == null ? null : lifecycle.registration();
        }
        if (value instanceof RegisteredObservation registration) {
            return registration;
        }
        throw new IllegalArgumentException("Registered observation has an unexpected type");
    }

    private LifecycleWriteContext lifecycleContext(Envelope<PreparedArtifacts> input) {
        Object value = input.meta().attributes().get(PipelineMetaAttributes.LIFECYCLE_WRITE_CONTEXT);
        if (value == null) {
            return null;
        }
        if (value instanceof LifecycleWriteContext context) {
            return context;
        }
        throw new IllegalArgumentException("Lifecycle write context has an unexpected type");
    }

    private DiagnosticException writeFailure(String sink, String artifact, RuntimeException failure) {
        var diagnostic = diagnosticFactory.create(SinkDiagnosticCodes.WRITE_FAILED)
                .with("sink", sink)
                .with("artifact", artifact)
                .with("reason", reason(failure))
                .cause(failure)
                .build();
        return new DiagnosticException(diagnostic);
    }

    private static String reason(RuntimeException failure) {
        return failure.getMessage() == null || failure.getMessage().isBlank()
                ? failure.getClass().getSimpleName()
                : failure.getMessage();
    }

    private record WriteOutcome(int inserted, boolean publicChanged) {
    }

    private record WriteExecution(Map<String, Integer> written,
                                  Set<String> changedArtifacts,
                                  List<Diagnostic> diagnostics) {

        private static WriteExecution empty() {
            return new WriteExecution(Map.of(), Set.of(), List.of());
        }
    }
}
