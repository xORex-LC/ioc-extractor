package com.iocextractor.application.pipeline.stage;

import com.iocextractor.application.pipeline.payload.AttributedIndicators;
import com.iocextractor.application.pipeline.payload.DeduplicatedIndicators;
import com.iocextractor.domain.model.Indicator;
import com.iocextractor.platform.etl.Envelope;
import com.iocextractor.platform.etl.Stage;
import com.iocextractor.platform.etl.StageId;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Removes within-batch duplicates. Storage-level keep-first semantics and
 * provenance accounting belong to the canonical artifact repository.
 */
public final class DeduplicateIndicatorsStage implements Stage<AttributedIndicators, DeduplicatedIndicators> {

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
    public Envelope<DeduplicatedIndicators> process(Envelope<AttributedIndicators> input) {
        var extracted = input.payload().indicators();
        var retained = deduplicate ? deduplicate(extracted) : extracted;
        return input.withPayload(new DeduplicatedIndicators(extracted.size(), retained));
    }

    private List<Indicator> deduplicate(List<Indicator> indicators) {
        Set<String> seen = new HashSet<>();
        List<Indicator> out = new ArrayList<>(indicators.size());
        for (Indicator indicator : indicators) {
            if (!seen.add(indicator.dedupKey())) {
                continue;
            }
            out.add(indicator);
        }
        return out;
    }
}
