package com.iocextractor.domain.attribute;

import com.iocextractor.domain.extract.PatternEngine;
import com.iocextractor.domain.extract.RawIndicator;
import com.iocextractor.domain.extract.Span;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Default {@link SourceAttributor}. Locates all section-marker occurrences, then
 * assigns each indicator the label of the nearest marker that precedes it.
 * Indicators with no preceding marker get a {@code null} source label, which the
 * sink renders as the CSV null literal (an empty {@code source} cell).
 */
public final class MarkerSourceAttributor implements SourceAttributor {

    private final List<PatternEngine.Compiled> markerPatterns;

    public MarkerSourceAttributor(PatternEngine engine, List<String> markerRegexes) {
        List<PatternEngine.Compiled> compiled = new ArrayList<>();
        for (String regex : markerRegexes) {
            compiled.add(engine.compile(regex));
        }
        this.markerPatterns = List.copyOf(compiled);
    }

    @Override
    public AttributionOutcome attribute(String text, List<RawIndicator> indicators) {
        List<SourceMarker> markers = collectMarkers(text);
        List<AttributionDecision> decisions = new ArrayList<>(indicators.size());
        for (RawIndicator raw : indicators) {
            decisions.add(new AttributionDecision(raw,
                    java.util.Optional.ofNullable(markerAt(markers, raw.position()))));
        }
        return new AttributionOutcome(markers, decisions);
    }

    private List<SourceMarker> collectMarkers(String text) {
        List<MarkerCandidate> candidates = new ArrayList<>();
        int patternOrder = 0;
        for (PatternEngine.Compiled pattern : markerPatterns) {
            for (Span span : pattern.findAll(text)) {
                candidates.add(new MarkerCandidate(
                        span.start(), span.end(), patternOrder, normalize(span.value())));
            }
            patternOrder++;
        }
        candidates.sort(Comparator.comparingInt(MarkerCandidate::start)
                .thenComparing(Comparator.comparingInt(MarkerCandidate::length).reversed())
                .thenComparingInt(MarkerCandidate::patternOrder));
        List<MarkerCandidate> selected = new ArrayList<>();
        for (MarkerCandidate candidate : candidates) {
            if (selected.isEmpty() || candidate.start() >= selected.getLast().end()) {
                selected.add(candidate);
            }
        }
        return selected.stream()
                .map(candidate -> new SourceMarker(candidate.start(), candidate.label()))
                .toList();
    }

    /** Nearest marker whose position is at or before {@code position}, or {@code null} if none. */
    private SourceMarker markerAt(List<SourceMarker> markers, int position) {
        SourceMarker selected = null;
        for (SourceMarker marker : markers) {
            if (marker.position() <= position) {
                selected = marker;
            } else {
                break;
            }
        }
        return selected;
    }

    private String normalize(String raw) {
        // Collapse whitespace runs incl. the non-breaking space (U+00A0) that the
        // Word export inserts and that the regex \s class does not match by default.
        return raw.replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
    }

    private record MarkerCandidate(int start, int end, int patternOrder, String label) {

        private int length() {
            return end - start;
        }
    }
}
