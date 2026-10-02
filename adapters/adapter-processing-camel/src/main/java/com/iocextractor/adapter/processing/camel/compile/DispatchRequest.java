package com.iocextractor.adapter.processing.camel.compile;

import com.iocextractor.adapter.processing.camel.contract.PlanExecutionResult.BranchInput;
import com.iocextractor.adapter.processing.camel.contract.PlanExecutionResult.BranchReply;
import java.util.List;
import org.apache.camel.Endpoint;

/** Adapter-internal request and aggregate for Camel's sequential recipient list. */
public record DispatchRequest(BranchInput input, List<Endpoint> recipients) {
    public DispatchRequest { recipients = List.copyOf(recipients); }

    /** Immutable ordered replies from selected local destinations. */
    public record Replies(List<BranchReply> values) {
        public Replies { values = List.copyOf(values); }
    }
}
