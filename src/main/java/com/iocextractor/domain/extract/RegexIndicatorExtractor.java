package com.iocextractor.domain.extract;

import com.iocextractor.domain.model.IndicatorType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Default {@link IndicatorExtractor}. Runs each type's pattern in priority order
 * (the iteration order of the supplied map) and claims character ranges so that
 * a span already attributed to a higher-priority type (URL, IP) cannot be
 * re-emitted by a lower-priority type (DOMAIN).
 */
public final class RegexIndicatorExtractor implements IndicatorExtractor {

    private record Entry(IndicatorType type, PatternEngine.Compiled compiled) {
    }

    private final List<Entry> entries;

    /**
     * @param engine   regex engine port
     * @param patterns ordered map type -> regex; iteration order defines priority
     */
    public RegexIndicatorExtractor(PatternEngine engine, Map<IndicatorType, String> patterns) {
        List<Entry> built = new ArrayList<>();
        patterns.forEach((type, regex) -> built.add(new Entry(type, engine.compile(regex))));
        this.entries = List.copyOf(built);
    }

    @Override
    public List<RawIndicator> extract(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        boolean[] claimed = new boolean[text.length()];
        List<RawIndicator> out = new ArrayList<>();

        for (Entry entry : entries) {
            for (Span span : entry.compiled().findAll(text)) {
                if (overlapsClaimed(claimed, span.start(), span.end())) {
                    continue;
                }
                claim(claimed, span.start(), span.end());
                out.add(new RawIndicator(span.value(), entry.type(), span.start()));
            }
        }
        out.sort(Comparator.comparingInt(RawIndicator::position));
        return out;
    }

    private boolean overlapsClaimed(boolean[] claimed, int start, int end) {
        for (int i = start; i < end; i++) {
            if (claimed[i]) {
                return true;
            }
        }
        return false;
    }

    private void claim(boolean[] claimed, int start, int end) {
        for (int i = start; i < end; i++) {
            claimed[i] = true;
        }
    }
}
