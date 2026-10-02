package com.iocextractor.adapter.processing.camel.compile;

import com.iocextractor.adapter.processing.camel.contract.PlanExecutionResult.BranchReply;
import java.util.Objects;
import org.apache.camel.Exchange;
import org.apache.camel.processor.aggregate.AbstractListAggregationStrategy;

/** Collects typed replies in an exchange-owned list with Camel-managed completion. */
final class BranchReplyAggregationStrategy extends AbstractListAggregationStrategy<BranchReply> {
    @Override public Exchange aggregate(Exchange previous, Exchange reply) {
        if (reply == null) {
            throw new IllegalStateException("Recipient list produced no reply");
        }
        return super.aggregate(previous, reply);
    }

    @Override public BranchReply getValue(Exchange exchange) {
        return Objects.requireNonNull(exchange.getMessage().getBody(BranchReply.class),
                "recipient reply");
    }
}
