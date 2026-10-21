package com.iocextractor.config.boot;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Name-only observations from one traversal, in source order and then raw-name order.
 * No entry asserts which value Binder eventually uses. Names and map keys can themselves
 * contain sensitive text; services must choose how to render them.
 *
 * @param declarations all declarations in the requested namespace, including unknowns
 * @param sources inspected and skipped sources in encounter order
 * @param schemaGaps unsupported schema paths, even when no source declares them
 */
public record PropertyInspection(List<Declaration> declarations, List<SourceCoverage> sources,
                                 List<SchemaGap> schemaGaps) {

    /** Copies result collections; no source, environment or mutable matcher state escapes. */
    public PropertyInspection {
        declarations = List.copyOf(declarations);
        sources = List.copyOf(sources);
        schemaGaps = List.copyOf(schemaGaps);
    }

    /**
     * Whether every relevant source and schema subtree was inspectable.
     * Known aggregate exclusions are intentional; unknown/ambiguous findings do not reduce coverage.
     *
     * @return true when coverage is complete, independently of service acceptance
     */
    public boolean complete() {
        return schemaGaps.isEmpty() && sources.stream().noneMatch(s -> s.state() == Coverage.INCOMPLETE)
                && declarations.stream().noneMatch(d -> d.status() == Status.UNSUPPORTED);
    }

    /** The distinction a service needs before choosing its own enforcement policy. */
    public enum Status {
        /** Exactly one supported canonical path. This says nothing about its value. */
        KNOWN,
        /** No supported path matches, and no relevant unsupported subtree could explain it. */
        UNKNOWN,
        /** Multiple canonical paths match the declaration. */
        AMBIGUOUS,
        /** The schema, name grammar or source convention cannot establish a complete answer. */
        UNSUPPORTED
    }

    /** Source categories have fixed labels and never contain arbitrary source names. */
    public enum SourceKind {
        /** An ordinary enumerable source. */
        PROPERTIES,
        /** An environment source using Boot's qualified naming convention. */
        ENVIRONMENT,
        /** A composite; children have their own encounter ordinals. */
        COMPOSITE,
        /** Boot's attached aggregate; excluded to avoid duplicate observations. */
        AGGREGATE,
        /** A source without enumerable names, or an unqualified source convention. */
        OTHER
    }

    /** What was possible for one source. Enumeration failures propagate instead of becoming coverage claims. */
    public enum Coverage {
        /** Names were enumerated. */
        ENUMERATED,
        /** Composite children were inspected separately. */
        EXPANDED,
        /** The known Boot aggregate was deliberately excluded. */
        EXCLUDED,
        /** Name inspection cannot cover this source. */
        INCOMPLETE
    }

    /**
     * Safe identity within this result, independent of a source's arbitrary label.
     * @param ordinal zero-based encounter ordinal, including composites and exclusions
     * @param kind fixed source category
     */
    public record SourceReference(int ordinal, SourceKind kind) {
        /** Rejects invalid identities. */
        public SourceReference {
            if (ordinal < 0) {
                throw new IllegalArgumentException("Negative source ordinal");
            }
            Objects.requireNonNull(kind, "kind");
        }
    }

    /**
     * A declaration, retaining every possible canonical path rather than picking an ambiguous winner.
     * @param rawName name as enumerated; may itself be sensitive
     * @param canonicalNames canonical candidates, in deterministic encounter order
     * @param status matching outcome
     * @param source safe source reference
     */
    public record Declaration(String rawName, Set<String> canonicalNames, Status status, SourceReference source) {
        /** Makes the candidate set immutable without discarding its order. */
        public Declaration {
            Objects.requireNonNull(rawName, "rawName");
            canonicalNames = Collections.unmodifiableSet(new LinkedHashSet<>(canonicalNames));
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(source, "source");
        }
    }

    /**
     * Coverage of one source; this carries neither its raw label nor its contents.
     * @param source safe source identity
     * @param state coverage outcome
     */
    public record SourceCoverage(SourceReference source, Coverage state) {
        /** Rejects missing coverage fields. */
        public SourceCoverage {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(state, "state");
        }
    }

    /**
     * An unsupported part of the record tree. Paths use {@code []} for list elements.
     * @param path canonical schema path
     * @param reason fixed description, with no source data or exception text
     */
    public record SchemaGap(String path, String reason) {
        /** Rejects missing descriptions. */
        public SchemaGap {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(reason, "reason");
        }
    }
}
