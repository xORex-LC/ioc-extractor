package com.iocextractor.adapter.processing.camel.contract;

import java.util.Objects;
import java.util.regex.Pattern;

/** Value-free evidence emitted only for reached routing work. */
public record RoutingTraceEvent(Kind kind, String planId, String viewId, String branchId,
                                String ruleId, String outcome, String reasonCode) {
    private static final Pattern CODE = Pattern.compile("[A-Za-z][A-Za-z0-9._-]{0,63}");

    public RoutingTraceEvent {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(planId);
        Objects.requireNonNull(outcome);
        if (ruleId != null && !isCode(ruleId)) {
            ruleId = "INVALID_RULE_ID";
        }
        if (reasonCode != null && !isCode(reasonCode)) {
            reasonCode = "INVALID_REASON_CODE";
        }
    }

    private static boolean isCode(String candidate) {
        return CODE.matcher(candidate).matches();
    }

    /** Technical event kind; severity and diagnostic delivery belong to the caller. */
    public enum Kind { VIEW, CONDITION, RECOVERY, BRANCH }
}
