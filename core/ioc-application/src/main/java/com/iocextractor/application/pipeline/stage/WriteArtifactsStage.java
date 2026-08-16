package com.iocextractor.application.pipeline.stage;

import com.iocextractor.application.pipeline.payload.ArtifactWriteSummary;
import com.iocextractor.application.pipeline.payload.PreparedArtifacts;
import com.iocextractor.application.pipeline.PipelineMetaAttributes;
import com.iocextractor.application.port.out.artifact.ArtifactProjection;
import com.iocextractor.application.port.out.artifact.ArtifactProjectionCommand;
import com.iocextractor.application.port.out.artifact.CanonicalArtifactRepository;
import com.iocextractor.application.artifact.lifecycle.CanonicalArtifactConfirmation;
import com.iocextractor.application.artifact.lifecycle.CanonicalRecordConfirmation;
import com.iocextractor.application.artifact.lifecycle.LifecycleWriteContext;
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
import java.util.Objects;

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
        var written = new LinkedHashMap<String, Integer>();
        var projectionDiagnostics = new ArrayList<Diagnostic>();
        if (!input.meta().booleanAttribute(PipelineMetaAttributes.DRY_RUN, false)) {
            LifecycleWriteContext lifecycle = lifecycleContext(input);
            if (lifecycle != null && lifecycle.receipt().expectedArtifacts() != payload.plans().size()) {
                throw new IllegalArgumentException(
                        "Lifecycle receipt artifact count does not match prepared plans");
            }
            for (var plan : payload.plans()) {
                int inserted;
                try {
                    inserted = lifecycle == null
                            ? repository.write(plan.artifactName(), plan.materialize()).inserted()
                            : confirm(plan, lifecycle);
                } catch (RuntimeException failure) {
                    throw writeFailure("canonical", plan.artifactName(), failure);
                }
                try {
                    var outcome = projection.project(new ArtifactProjectionCommand(
                            input.meta().runId(), plan.artifactName()));
                    projectionDiagnostics.addAll(outcome.diagnostics());
                } catch (RuntimeException failure) {
                    throw writeFailure("projection", plan.artifactName(), failure);
                }
                written.put(plan.artifactName(), inserted);
            }
        }
        return input.withPayload(new ArtifactWriteSummary(
                payload.extracted(),
                payload.retained(),
                written)).withDiagnostics(projectionDiagnostics);
    }

    private int confirm(com.iocextractor.application.artifact.ArtifactWritePlan plan,
                        LifecycleWriteContext context) {
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
        return lifecycleWriter.confirm(new CanonicalArtifactConfirmation(
                context.observationId(),
                context.sourceKey(),
                context.receipt(),
                plan.artifactName(),
                plan.header(),
                records)).publicRowsInserted();
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
}
