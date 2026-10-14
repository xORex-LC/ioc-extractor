package com.iocextractor.bootstrap;

import com.iocextractor.application.observation.OccurrencePosition;
import com.iocextractor.adapter.out.sink.csv.CsvArtifactPreparer;
import com.iocextractor.processing.model.ClassifiedIndicator;
import com.iocextractor.processing.session.IndicatorProcessingSession;
import java.util.Map;
import java.util.Objects;

/** IOC view with invocation-local ordering, shared by document and import paths. */
record ProcessingView(ClassifiedIndicator classified, OccurrencePosition position, int ordinal,
                      Map<String, CsvArtifactPreparer> preparers, IndicatorProcessingSession session,
                      boolean retainedObservation) {
    ProcessingView {
        Objects.requireNonNull(classified, "classified");
        Objects.requireNonNull(position, "position");
        if (ordinal < 0) {
            throw new IllegalArgumentException("Processing ordinal must be nonnegative");
        }
        preparers = Map.copyOf(preparers);
    }

    ProcessingView(ClassifiedIndicator classified, OccurrencePosition position, int ordinal,
                   Map<String, CsvArtifactPreparer> preparers, IndicatorProcessingSession session) {
        this(classified, position, ordinal, preparers, session, true);
    }

    ProcessingView(ClassifiedIndicator classified, OccurrencePosition position, int ordinal,
                   Map<String, CsvArtifactPreparer> preparers) {
        this(classified, position, ordinal, preparers, null);
    }

    ProcessingView(ClassifiedIndicator classified, OccurrencePosition position, int ordinal) {
        this(classified, position, ordinal, Map.of());
    }

    ProcessingView derived(ClassifiedIndicator result) {
        return new ProcessingView(result, position, ordinal, preparers, session, retainedObservation);
    }
}
