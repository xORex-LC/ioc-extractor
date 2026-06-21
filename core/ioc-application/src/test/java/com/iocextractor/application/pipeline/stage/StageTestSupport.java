package com.iocextractor.application.pipeline.stage;

import com.iocextractor.application.pipeline.PipelineMetaAttributes;
import com.iocextractor.platform.etl.Envelope;
import com.iocextractor.platform.etl.EnvelopeMeta;
import com.iocextractor.application.port.in.ExtractionCommand;
import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.domain.model.SourceContext;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

final class StageTestSupport {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-21T00:00:00Z"), ZoneOffset.UTC);

    private StageTestSupport() {
    }

    static Envelope<ExtractionCommand> commandEnvelope(boolean dryRun) {
        var command = new ExtractionCommand(Path.of("input.html"), dryRun);
        return Envelope.of(command, meta(command.source(), dryRun));
    }

    static <T> Envelope<T> envelope(T payload, boolean dryRun) {
        return Envelope.of(payload, meta(Path.of("input.html"), dryRun));
    }

    private static EnvelopeMeta meta(Path source, boolean dryRun) {
        var normalized = source.toAbsolutePath().normalize();
        return EnvelopeMeta.initial("run-1", normalized.toString(), CLOCK)
                .withAttribute(PipelineMetaAttributes.SOURCE_PATH, normalized)
                .withAttribute(PipelineMetaAttributes.DRY_RUN, dryRun);
    }

    static Indicator indicator(String value) {
        return new Indicator(value, IndicatorType.DOMAIN, SourceContext.UNKNOWN);
    }
}
