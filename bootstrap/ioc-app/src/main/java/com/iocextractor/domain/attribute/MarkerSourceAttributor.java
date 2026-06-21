package com.iocextractor.domain.attribute;

import com.iocextractor.domain.extract.PatternEngine;
import com.iocextractor.domain.extract.RawIndicator;
import com.iocextractor.domain.extract.Span;
import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.SourceContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Default {@link SourceAttributor}. Locates all section-marker occurrences, then
 * assigns each indicator the label of the nearest marker that precedes it.
 * Indicators before the first marker get {@link SourceContext#UNKNOWN}.
 */
public final class MarkerSourceAttributor implements SourceAttributor {

    private record Marker(int position, String label) {
    }

    private final List<PatternEngine.Compiled> markerPatterns;

    public MarkerSourceAttributor(PatternEngine engine, List<String> markerRegexes) {
        List<PatternEngine.Compiled> compiled = new ArrayList<>();
        for (String regex : markerRegexes) {
            compiled.add(engine.compile(regex));
        }
        this.markerPatterns = List.copyOf(compiled);
    }

    @Override
    public List<Indicator> attribute(String text, List<RawIndicator> indicators) {
        List<Marker> markers = collectMarkers(text);
        List<Indicator> out = new ArrayList<>(indicators.size());
        for (RawIndicator raw : indicators) {
            String label = labelAt(markers, raw.position());
            out.add(new Indicator(raw.value(), raw.type(), new SourceContext(label, null)));
        }
        return out;
    }

    private List<Marker> collectMarkers(String text) {
        List<Marker> markers = new ArrayList<>();
        for (PatternEngine.Compiled pattern : markerPatterns) {
            for (Span span : pattern.findAll(text)) {
                markers.add(new Marker(span.start(), normalize(span.value())));
            }
        }
        markers.sort(Comparator.comparingInt(Marker::position));
        return markers;
    }

    /** Nearest marker whose position is at or before {@code position}. */
    private String labelAt(List<Marker> markers, int position) {
        String label = SourceContext.UNKNOWN.label();
        for (Marker marker : markers) {
            if (marker.position() <= position) {
                label = marker.label();
            } else {
                break;
            }
        }
        return label;
    }

    private String normalize(String raw) {
        // Collapse whitespace runs incl. the non-breaking space (U+00A0) that the
        // Word export inserts and that the regex \s class does not match by default.
        return raw.replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
    }
}
