package com.iocextractor.application.pipeline.stage;

import com.iocextractor.application.pipeline.PipelineMetaAttributes;
import com.iocextractor.platform.etl.Envelope;
import com.iocextractor.platform.etl.EnvelopeMeta;
import com.iocextractor.application.port.in.ExtractionCommand;
import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.domain.model.SourceContext;
import com.iocextractor.application.pipeline.payload.AttributedIndicators;
import com.iocextractor.application.pipeline.payload.ClassifiedIndicator;
import com.iocextractor.application.pipeline.payload.DeduplicationDecision;
import com.iocextractor.application.pipeline.payload.DeduplicatedIndicators;
import com.iocextractor.application.observability.NoopPipelineDecisionTracer;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.domain.attribute.AttributionDecision;
import com.iocextractor.domain.attribute.AttributionOutcome;
import com.iocextractor.domain.attribute.SourceMarker;
import com.iocextractor.domain.extract.RawIndicator;
import com.iocextractor.domain.classify.ClassificationDecision;
import com.iocextractor.domain.feature.HostKind;
import com.iocextractor.domain.feature.IndicatorFeatures;
import com.iocextractor.domain.model.MaskMatch;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.List;

final class StageTestSupport {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-21T00:00:00Z"), ZoneOffset.UTC);
    static final DiagnosticFactory DIAGNOSTICS = new DiagnosticFactory(CLOCK);
    static final NoopPipelineDecisionTracer TRACER = NoopPipelineDecisionTracer.INSTANCE;

    private StageTestSupport() {
    }

    static Envelope<ExtractionCommand> commandEnvelope(boolean dryRun) {
        var command = new ExtractionCommand("test-run", Path.of("input.html"), dryRun);
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
        return new Indicator(value, IndicatorType.DOMAIN, new SourceContext("test-source", null));
    }

    static AttributionOutcome attributionOutcome(Indicator... indicators) {
        var decisions = java.util.Arrays.stream(indicators)
                .map(indicator -> {
                    var raw = new RawIndicator(indicator.value(), indicator.type(), 0);
                    var marker = Optional.ofNullable(indicator.source().label())
                            .map(label -> new SourceMarker(0, label));
                    return new AttributionDecision(raw, marker);
                })
                .toList();
        var markers = decisions.stream().flatMap(decision -> decision.marker().stream()).distinct().toList();
        return new AttributionOutcome(markers, decisions);
    }

    static AttributedIndicators attributedIndicators(Indicator... indicators) {
        return new AttributedIndicators(attributionOutcome(indicators));
    }

    static ClassifiedIndicator classifiedIndicator(Indicator indicator) {
        var features = new IndicatorFeatures(
                indicator.value(), indicator.value(), false, false, false, HostKind.REGISTRABLE);
        return new ClassifiedIndicator(indicator,
                new ClassificationDecision(features, 0, List.of(), new MaskMatch("u:hAS", "h:dAS")));
    }

    static DeduplicatedIndicators deduplicatedIndicators(Indicator... indicators) {
        var retained = List.of(indicators);
        var decisions = retained.stream()
                .map(indicator -> new DeduplicationDecision(indicator, true))
                .toList();
        return new DeduplicatedIndicators(indicators.length, retained, decisions);
    }
}
