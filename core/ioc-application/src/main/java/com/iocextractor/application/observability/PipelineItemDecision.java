package com.iocextractor.application.observability;

import java.util.Objects;

/**
 * Compact application-owned representation of one already-computed pipeline decision.
 * Optional attributes remain {@code null} when they do not apply to a decision kind.
 */
public record PipelineItemDecision(PipelineDecisionKind kind,
                                   String outcome,
                                   String identity,
                                   String indicatorType,
                                   String value,
                                   String rule,
                                   String pattern,
                                   String result,
                                   Integer spanStart,
                                   Integer spanEnd,
                                   String artifact) {

    public PipelineItemDecision {
        Objects.requireNonNull(kind, "kind");
        requireText(outcome, "outcome");
        if (spanStart != null && spanStart < 0) {
            throw new IllegalArgumentException("spanStart must be non-negative");
        }
        if (spanEnd != null && spanEnd < 0) {
            throw new IllegalArgumentException("spanEnd must be non-negative");
        }
        if (spanStart != null && spanEnd != null && spanEnd < spanStart) {
            throw new IllegalArgumentException("spanEnd must not precede spanStart");
        }
    }

    /** Starts a decision builder with its mandatory stable attributes. */
    public static Builder builder(PipelineDecisionKind kind, String outcome) {
        return new Builder(kind, outcome);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    /** Builder for kind-specific optional decision attributes. */
    public static final class Builder {

        private final PipelineDecisionKind kind;
        private final String outcome;
        private String identity;
        private String indicatorType;
        private String value;
        private String rule;
        private String pattern;
        private String result;
        private Integer spanStart;
        private Integer spanEnd;
        private String artifact;

        private Builder(PipelineDecisionKind kind, String outcome) {
            this.kind = Objects.requireNonNull(kind, "kind");
            this.outcome = requireText(outcome, "outcome");
        }

        /** Sets a producer-defined safe identity when raw-value hashing does not apply. */
        public Builder identity(String identity) {
            this.identity = identity;
            return this;
        }

        /** Sets the item type and trusted in-process raw value. */
        public Builder item(String indicatorType, String value) {
            this.indicatorType = indicatorType;
            this.value = value;
            return this;
        }

        /** Sets the selected rule or marker identity. */
        public Builder rule(String rule) {
            this.rule = rule;
            return this;
        }

        /** Sets the evaluated extraction pattern or classification predicates. */
        public Builder pattern(String pattern) {
            this.pattern = pattern;
            return this;
        }

        /** Sets a compact already-materialized result. */
        public Builder result(String result) {
            this.result = result;
            return this;
        }

        /** Sets the half-open source span. */
        public Builder span(int start, int end) {
            this.spanStart = start;
            this.spanEnd = end;
            return this;
        }

        /** Sets the artifact involved in a routing decision. */
        public Builder artifact(String artifact) {
            this.artifact = artifact;
            return this;
        }

        /** Builds the immutable decision. */
        public PipelineItemDecision build() {
            return new PipelineItemDecision(
                    kind, outcome, identity, indicatorType, value, rule, pattern, result,
                    spanStart, spanEnd, artifact);
        }
    }
}
