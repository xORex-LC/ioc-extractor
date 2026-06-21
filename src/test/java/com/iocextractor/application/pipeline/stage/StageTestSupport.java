package com.iocextractor.application.pipeline.stage;

import com.iocextractor.application.pipeline.Envelope;
import com.iocextractor.application.pipeline.EnvelopeMeta;
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
        return Envelope.of(command, EnvelopeMeta.initial("run-1", command.source(), dryRun, CLOCK));
    }

    static <T> Envelope<T> envelope(T payload, boolean dryRun) {
        return Envelope.of(payload, EnvelopeMeta.initial("run-1", Path.of("input.html"), dryRun, CLOCK));
    }

    static Indicator indicator(String value) {
        return new Indicator(value, IndicatorType.DOMAIN, SourceContext.UNKNOWN);
    }
}
