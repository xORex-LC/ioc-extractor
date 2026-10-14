package com.iocextractor.application;

import com.iocextractor.application.port.out.SourceReader;
import com.iocextractor.application.port.out.artifact.DocumentSourceWorkspace;
import com.iocextractor.application.port.out.artifact.RowSource;
import com.iocextractor.domain.attribute.AttributionDecision;
import com.iocextractor.domain.extract.ExtractionDecision;
import com.iocextractor.domain.extract.ExtractionDecisionStatus;
import com.iocextractor.domain.extract.ExtractionOutcome;
import com.iocextractor.domain.extract.IndicatorExtractor;
import com.iocextractor.domain.extract.RawIndicator;
import com.iocextractor.domain.extract.Span;
import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.domain.refang.RefangRule;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** Explicit finite source fixture; production never falls back to this implementation. */
public final class TestDocumentSourceWorkspace implements DocumentSourceWorkspace {
    private final StringWriter output;
    private String rewritten;
    private final List<ExtractionDecision> decisions = new ArrayList<>();
    private final List<AttributionDecision> attributed = new ArrayList<>();
    public TestDocumentSourceWorkspace() { this(new StringWriter()); }
    public TestDocumentSourceWorkspace(StringWriter output) { this.output = output; }
    public Writer writer() { return output; }
    public CharSequence text() { return rewritten == null ? output.toString() : rewritten; }
    public int maximumMatchCharacters() { return 65536; }
    public boolean isEmpty() { return text().isEmpty(); }
    public int replace(RefangRule rule) {
        String before = text().toString();
        int count = rule.from().isEmpty() ? before.length() + 1 : 0;
        if (!rule.from().isEmpty()) {
            for (int offset = 0; (offset = before.indexOf(rule.from(), offset)) >= 0; offset += rule.from().length()) { count++; }
        }
        rewritten = before.replace(rule.from(), rule.to()); return count;
    }
    public boolean overlaps(int start, int end) {
        return decisions.stream().filter(value -> value.status() == ExtractionDecisionStatus.ACCEPTED)
                .anyMatch(value -> end > start && value.span().end() > value.span().start()
                        && value.span().start() < end && value.span().end() > start);
    }
    public void record(ExtractionDecision decision) { decisions.add(decision); }
    public Optional<IndicatorType> acceptedType(int start, int end) {
        return decisions.stream().filter(value -> value.status() == ExtractionDecisionStatus.ACCEPTED
                && value.span().start() == start && value.span().end() == end).map(ExtractionDecision::type).findFirst();
    }
    public RowSource<ExtractionDecision> decisions() { return RowSource.of(decisions); }
    public RowSource<RawIndicator> indicators() {
        return RowSource.of(decisions.stream().filter(value -> value.status() == ExtractionDecisionStatus.ACCEPTED)
                .map(value -> new RawIndicator(value.span().value(), value.type(), value.span().start()))
                .sorted(Comparator.comparingInt(RawIndicator::position)).toList());
    }
    public void attribute(AttributionDecision decision) { attributed.add(decision); }
    public RowSource<AttributionDecision> attributions() { return RowSource.of(attributed); }
    public void close() { }

    public static SourceReader reader(Function<Path, String> function) {
        return new SourceReader() {
            public String readText(Path path) { return function.apply(path); }
            public void readText(Path path, Writer writer) {
                try { writer.write(function.apply(path)); }
                catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
            }
        };
    }
    public static IndicatorExtractor extractor(Function<String, ExtractionOutcome> function) {
        return new IndicatorExtractor() {
            public ExtractionOutcome extract(String text) { return function.apply(text); }
            public void extract(CharSequence text, int limit, com.iocextractor.domain.extract.ExtractionClaims claims) {
                var outcome = function.apply(text.toString());
                for (var raw : outcome.indicators()) {
                    claims.record(new ExtractionDecision(raw.type(), "finite-fixture", new Span(raw.position(),
                            raw.position() + raw.value().length(), raw.value()), ExtractionDecisionStatus.ACCEPTED));
                }
            }
        };
    }
    public static com.iocextractor.domain.attribute.SourceAttributor noMarkers() {
        return new com.iocextractor.domain.attribute.SourceAttributor() {
            public com.iocextractor.domain.attribute.AttributionOutcome attribute(String text, List<RawIndicator> raw) {
                return new com.iocextractor.domain.attribute.AttributionOutcome(List.of(), raw.stream()
                        .map(value -> new AttributionDecision(value, Optional.empty())).toList());
            }
            public com.iocextractor.domain.attribute.MarkerCursor markers(CharSequence text, int limit) {
                return new com.iocextractor.domain.attribute.MarkerCursor() {
                    public boolean next() { return false; }
                    public com.iocextractor.domain.attribute.SourceMarker value() { throw new IllegalStateException("No markers"); }
                };
            }
        };
    }
}
