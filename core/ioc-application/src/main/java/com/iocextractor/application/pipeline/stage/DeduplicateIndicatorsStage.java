package com.iocextractor.application.pipeline.stage;

import com.iocextractor.platform.etl.Envelope;
import com.iocextractor.platform.etl.Stage;
import com.iocextractor.platform.etl.StageId;
import com.iocextractor.application.pipeline.payload.ClassifiedIndicator;
import com.iocextractor.application.pipeline.payload.ClassifiedIndicators;
import com.iocextractor.application.pipeline.payload.RetainedIndicators;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Removes within-batch duplicates. Storage-level keep-first semantics and
 * provenance accounting belong to the canonical artifact repository.
 */
public final class DeduplicateIndicatorsStage implements Stage<ClassifiedIndicators, RetainedIndicators> {

    private final boolean deduplicate;

    /**
     * Creates the stage.
     *
     * @param deduplicate whether within-batch de-duplication is enabled
     */
    public DeduplicateIndicatorsStage(boolean deduplicate) {
        this.deduplicate = deduplicate;
    }

    @Override
    public StageId name() {
        return StageNames.DEDUPLICATE;
    }

    @Override
    public Envelope<RetainedIndicators> process(Envelope<ClassifiedIndicators> input) {
        var extracted = input.payload().indicators();
        var retained = deduplicate ? deduplicate(extracted) : extracted;
        return input.withPayload(new RetainedIndicators(extracted, retained));
    }

    private List<ClassifiedIndicator> deduplicate(List<ClassifiedIndicator> indicators) {
        Set<String> seen = new HashSet<>();
        List<ClassifiedIndicator> out = new ArrayList<>(indicators.size());
        for (ClassifiedIndicator indicator : indicators) {
            if (!seen.add(indicator.indicator().dedupKey())) {
                continue;
            }
            out.add(indicator);
        }
        return out;
    }
}
