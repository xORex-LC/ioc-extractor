package com.iocextractor.application.pipeline.stage;

import com.iocextractor.application.pipeline.Envelope;
import com.iocextractor.application.pipeline.Stage;
import com.iocextractor.application.pipeline.StageId;
import com.iocextractor.application.pipeline.payload.AttributedIndicators;
import com.iocextractor.application.pipeline.payload.RetainedIndicators;
import com.iocextractor.application.port.out.LookupRepository;
import com.iocextractor.domain.model.Indicator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Removes within-batch duplicates and indicators already present in lookup
 * storage.
 */
public final class DeduplicateIndicatorsStage implements Stage<AttributedIndicators, RetainedIndicators> {

    private final LookupRepository lookup;
    private final boolean deduplicate;

    /**
     * Creates the stage.
     *
     * @param lookup lookup repository
     * @param deduplicate whether de-duplication is enabled
     */
    public DeduplicateIndicatorsStage(LookupRepository lookup, boolean deduplicate) {
        this.lookup = Objects.requireNonNull(lookup, "lookup");
        this.deduplicate = deduplicate;
    }

    @Override
    public StageId name() {
        return StageNames.DEDUPLICATE;
    }

    @Override
    public Envelope<RetainedIndicators> process(Envelope<AttributedIndicators> input) {
        var extracted = input.payload().indicators();
        var retained = deduplicate ? deduplicate(extracted) : extracted;
        return input.withPayload(new RetainedIndicators(extracted, retained));
    }

    private List<Indicator> deduplicate(List<Indicator> indicators) {
        Set<String> seen = new HashSet<>();
        List<Indicator> out = new ArrayList<>(indicators.size());
        for (Indicator indicator : indicators) {
            if (!seen.add(indicator.dedupKey())) {
                continue;
            }
            if (lookup.contains(indicator)) {
                continue;
            }
            out.add(indicator);
        }
        return out;
    }
}
