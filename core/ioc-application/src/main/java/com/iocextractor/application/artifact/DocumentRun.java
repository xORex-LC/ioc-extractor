package com.iocextractor.application.artifact;

import com.iocextractor.application.ingest.SourceKey;
import com.iocextractor.application.observation.RegisteredObservation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One service-side run bound to the original document registration and accepted plan. */
public record DocumentRun(IngestRun run, Identity identity, Optional<Plan> plan) {

    public DocumentRun {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(plan, "plan");
        if (!run.sourceKey().equals(identity.sourceKey().value())
                || !run.artifacts().equals(plan.map(Plan::artifacts).orElseGet(List::of))) {
            throw new IllegalArgumentException("Document run identity or planned artifacts conflict");
        }
    }

    /** Policy, lifecycle and dataframe namespace pinned before canonical promotion. */
    public record Identity(RegisteredObservation registration, SourceKey sourceKey,
                           String policy, boolean lifecycleActive) {
        public Identity {
            Objects.requireNonNull(registration, "registration");
            Objects.requireNonNull(sourceKey, "sourceKey");
            Objects.requireNonNull(policy, "policy");
        }
    }

    /** An approved, ordered artifact list and either a workspace seal or the exact receipt ID. */
    public record Plan(Kind kind, String token, List<String> artifacts) {
        public Plan {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(token, "token");
            artifacts = List.copyOf(artifacts);
            if (token.isBlank() || artifacts.stream().anyMatch(Plan::invalidArtifact)
                    || artifacts.stream().distinct().count() != artifacts.size()) {
                throw new IllegalArgumentException("Invalid durable document plan");
            }
        }

        private static boolean invalidArtifact(String name) {
            return name.isBlank() || name.indexOf('\n') >= 0;
        }

        public enum Kind { WORKSPACE, RECEIPT }
    }
}
