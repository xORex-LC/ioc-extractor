package com.iocextractor.adapter.processing.camel.compile;

import com.iocextractor.adapter.processing.camel.contract.PlanExecutionResult.BranchReply;
import com.iocextractor.adapter.processing.camel.contract.BranchOutcome;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.camel.AggregationStrategy;
import org.apache.camel.Exchange;

/** Collects sequential recipient replies without sharing state across invocations. */
final class BranchReplyAggregationStrategy implements AggregationStrategy {
    @Override public Exchange aggregate(Exchange previous, Exchange reply) {
        if (reply == null) {
            throw new IllegalStateException("Recipient list produced no reply");
        }
        List<BranchReply> values = new ArrayList<>();
        if (previous != null) {
            DispatchRequest.Replies earlier = Objects.requireNonNull(
                    previous.getMessage().getBody(DispatchRequest.Replies.class),
                    "previous recipient replies");
            values.addAll(earlier.values());
        }
        String branchId = Objects.requireNonNull(
                reply.getMessage().getHeader(RouteProtocol.BRANCH_ID, String.class),
                "recipient branch ID");
        BranchOutcome outcome = Objects.requireNonNull(
                reply.getMessage().getBody(BranchOutcome.class), "recipient outcome");
        values.add(new BranchReply(branchId, outcome));
        Exchange result = previous == null ? reply : previous;
        result.getMessage().setBody(new DispatchRequest.Replies(values));
        return result;
    }
}
