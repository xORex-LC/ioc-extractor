package com.iocextractor.adapter.processing.camel.compile;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class BranchReplyAggregationStrategyTest {
    @Test void rejectsMissingRecipientReplyInsteadOfReturningPartialResults() {
        assertThatThrownBy(() -> new BranchReplyAggregationStrategy().aggregate(null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Recipient list produced no reply");
    }
}
