package com.iocextractor.application.pipeline.stage;

import com.iocextractor.application.pipeline.Envelope;
import com.iocextractor.application.pipeline.EnvelopeMeta;
import com.iocextractor.application.pipeline.Stage;
import com.iocextractor.application.pipeline.StageName;
import com.iocextractor.application.pipeline.payload.ArtifactWriteSummary;
import com.iocextractor.application.pipeline.payload.RetainedIndicators;
import com.iocextractor.application.port.out.IocSink;

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
    public StageName name() {
        return StageName.WRITE_ARTIFACTS;
    }

    @Override
    public Envelope<ArtifactWriteSummary> process(Envelope<RetainedIndicators> input) {
        var payload = input.payload();
        var written = new LinkedHashMap<String, Integer>();
        if (!input.meta().booleanAttribute(EnvelopeMeta.DRY_RUN, false)) {
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
