package com.iocextractor.application.pipeline.stage;

import com.iocextractor.application.artifact.ArtifactWritePlan;
import com.iocextractor.application.pipeline.payload.PreparedArtifacts;
import com.iocextractor.application.pipeline.payload.RetainedIndicators;
import com.iocextractor.application.port.out.artifact.ArtifactPreparer;
import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.platform.etl.Envelope;
import com.iocextractor.platform.etl.Stage;
import com.iocextractor.platform.etl.StageId;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Prepares every artifact without durable side effects, before failure-policy evaluation. */
public final class PrepareArtifactsStage implements Stage<RetainedIndicators, PreparedArtifacts> {

    private final List<ArtifactPreparer> preparers;

    /** Creates the preparation stage for the configured artifacts. */
    public PrepareArtifactsStage(List<ArtifactPreparer> preparers) {
        this.preparers = List.copyOf(Objects.requireNonNull(preparers, "preparers"));
    }

    @Override
    public StageId name() {
        return StageNames.PREPARE_ARTIFACTS;
    }

    @Override
    public Envelope<PreparedArtifacts> process(Envelope<RetainedIndicators> input) {
        var plans = new ArrayList<ArtifactWritePlan>(preparers.size());
        var diagnostics = new ArrayList<Diagnostic>();
        for (ArtifactPreparer preparer : preparers) {
            var result = preparer.prepare(input.payload().retained());
            plans.add(Objects.requireNonNull(result.value(), "prepared plan"));
            diagnostics.addAll(result.diagnostics());
        }
        var prepared = new PreparedArtifacts(
                input.payload().extracted().size(), input.payload().retained().size(), plans);
        return input.withPayload(prepared).withDiagnostics(diagnostics);
    }
}
