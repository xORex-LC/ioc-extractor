package com.iocextractor.adapter.processing.camel.compile;

import com.iocextractor.adapter.processing.camel.contract.BranchOutcome;
import com.iocextractor.adapter.processing.camel.contract.PlanExecutionResult.BranchReply;
import java.util.ArrayList;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class BranchReplyAggregationStrategyTest {
    @Test void rejectsMissingRecipientReplyInsteadOfReturningPartialResults() {
        assertThatThrownBy(() -> new BranchReplyAggregationStrategy().aggregate(null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Recipient list produced no reply");
    }

    @Test void rejectsAnExchangeWithNoTypedReply() throws Exception {
        try (var context = new DefaultCamelContext()) {
            var strategy = new BranchReplyAggregationStrategy();
            assertThatThrownBy(() -> strategy.aggregate(null, new DefaultExchange(context)))
                    .isInstanceOf(NullPointerException.class).hasMessage("recipient reply");
        }
    }

    @Test void completesAnOrderedListWithoutSharingStateBetweenInvocations() throws Exception {
        try (var context = new DefaultCamelContext()) {
            var strategy = new BranchReplyAggregationStrategy();
            Exchange first = null;
            Exchange second = null;
            var expectedFirst = new ArrayList<BranchReply>();
            var expectedSecond = new ArrayList<BranchReply>();
            for (int index = 0; index < 64; index++) {
                var one = new BranchReply("first-" + index, new BranchOutcome.Prepared(index));
                var two = new BranchReply("second-" + index, new BranchOutcome.Filtered());
                expectedFirst.add(one);
                expectedSecond.add(two);
                first = strategy.aggregate(first, reply(context, one));
                second = strategy.aggregate(second, reply(context, two));
            }
            strategy.onCompletion(first);
            strategy.onCompletion(second);
            java.util.List<?> firstValues = first.getMessage().getBody(java.util.List.class);
            java.util.List<?> secondValues = second.getMessage().getBody(java.util.List.class);
            assertThat(firstValues.toArray()).containsExactlyElementsOf(expectedFirst);
            assertThat(secondValues.toArray()).containsExactlyElementsOf(expectedSecond);
            expectedFirst.clear();
            assertThat(firstValues).hasSize(64);
        }
    }

    private static Exchange reply(DefaultCamelContext context, BranchReply value) {
        var exchange = new DefaultExchange(context);
        exchange.getMessage().setBody(value);
        return exchange;
    }
}
