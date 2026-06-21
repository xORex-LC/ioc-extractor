package com.iocextractor.application.pipeline.stage;

import com.iocextractor.application.pipeline.payload.ArtifactWriteSummary;
import com.iocextractor.application.pipeline.payload.RetainedIndicators;
import com.iocextractor.application.pipeline.PipelineMetaAttributes;
import com.iocextractor.application.port.out.IocSink;
import com.iocextractor.platform.etl.Envelope;
import com.iocextractor.platform.etl.Stage;
import com.iocextractor.platform.etl.StageId;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Writes retained indicators to configured sinks unless dry-run is enabled.
 */
public final class WriteArtifactsStage implements Stage<RetainedIndicators, ArtifactWriteSummary> {

    private final List<IocSink> sinks;

    /**
     * Creates the stage.
     *
     * @param sinks artifact sinks
     */
    public WriteArtifactsStage(List<IocSink> sinks) {
        this.sinks = List.copyOf(Objects.requireNonNull(sinks, "sinks"));
    }

    @Override
    public StageId name() {
        return StageNames.WRITE_ARTIFACTS;
    }

    @Override
    public Envelope<ArtifactWriteSummary> process(Envelope<RetainedIndicators> input) {
        var payload = input.payload();
        var written = new LinkedHashMap<String, Integer>();
        if (!input.meta().booleanAttribute(PipelineMetaAttributes.DRY_RUN, false)) {
            for (IocSink sink : sinks) {
                written.put(sink.name(), sink.write(payload.retained()));
            }
        }
        return input.withPayload(new ArtifactWriteSummary(
                payload.extracted().size(),
                payload.retained().size(),
                written));
    }
}
