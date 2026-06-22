package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.domain.model.Indicator;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Artifact-level indicator filter. Type routing remains separate; this filter
 * adds declarative feature predicates such as "bare IP only" or "exclude bare IP".
 */
public final class ArtifactFilter {

    private static final ArtifactFilter NONE = new ArtifactFilter(List.of(), List.of());

    private final List<Predicate<Indicator>> include;
    private final List<Predicate<Indicator>> exclude;

    /**
     * Creates a filter.
     *
     * @param include predicates that must all match; empty means include by default
     * @param exclude predicates that reject an indicator when any matches
     */
    public ArtifactFilter(List<Predicate<Indicator>> include, List<Predicate<Indicator>> exclude) {
        this.include = List.copyOf(Objects.requireNonNull(include, "include"));
        this.exclude = List.copyOf(Objects.requireNonNull(exclude, "exclude"));
    }

    /**
     * Returns a pass-through filter.
     *
     * @return filter that accepts every indicator
     */
    public static ArtifactFilter none() {
        return NONE;
    }

    /**
     * Tests whether an indicator should be written to the artifact.
     *
     * @param indicator indicator to test
     * @return true when included and not excluded
     */
    public boolean accepts(Indicator indicator) {
        Objects.requireNonNull(indicator, "indicator");
        for (Predicate<Indicator> predicate : include) {
            if (!predicate.test(indicator)) {
                return false;
            }
        }
        for (Predicate<Indicator> predicate : exclude) {
            if (predicate.test(indicator)) {
                return false;
            }
        }
        return true;
    }
}
