package com.iocextractor.config.boot;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Migration observations derived from an existing name snapshot. Unknown removed names
 * stay unknown in that snapshot; a migration hint does not make them bindable.
 *
 * @param inspection original declarations and coverage, including sources that could not be inspected
 * @param observations declarations matching at least one complete old path, in declaration order
 */
public record MigrationInspection(PropertyInspection inspection, List<Observation> observations) {

    /** Retains the immutable snapshot and copies observations. */
    public MigrationInspection {
        Objects.requireNonNull(inspection, "inspection");
        observations = List.copyOf(observations);
    }

    /**
     * Whether the original name inspection covered every source and schema subtree.
     * Even complete coverage does not imply that ambiguous observations are resolved.
     * @return the original inspection's coverage result
     */
    public boolean complete() {
        return inspection.complete();
    }

    /** How confidently a declaration can be associated with an old name. */
    public enum Resolution {
        /** One old path, with no competing path in the current schema. */
        RESOLVED,
        /** The declaration also matches another path; consumers must not pick a winner. */
        AMBIGUOUS,
        /** The original schema or source convention cannot establish a complete answer. */
        UNSUPPORTED
    }

    /**
     * One concrete interpretation. Indexes are copied in order from the old declaration.
     * @param migration service descriptor
     * @param path concrete old path
     * @param replacement concrete replacement, if supplied by the service
     */
    public record Match(PropertyMigration migration, String path, Optional<String> replacement) {
        /** Rejects missing result fields. */
        public Match {
            Objects.requireNonNull(migration, "migration");
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(replacement, "replacement");
        }
    }

    /**
     * A declaration may have several interpretations; none implies an effective bound value.
     * @param declaration original name and safe source reference
     * @param matches complete matching old paths
     * @param resolution whether these matches can be resolved without guessing
     */
    public record Observation(PropertyInspection.Declaration declaration, List<Match> matches, Resolution resolution) {
        /** Copies matches and rejects observations without an old path. */
        public Observation {
            Objects.requireNonNull(declaration, "declaration");
            matches = List.copyOf(matches);
            if (matches.isEmpty()) {
                throw new IllegalArgumentException("Migration observation requires a match");
            }
            Objects.requireNonNull(resolution, "resolution");
        }
    }
}
