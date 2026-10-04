package com.iocextractor.application.pipeline.stage;

import com.iocextractor.application.artifact.ArtifactRowKey;
import com.iocextractor.application.artifact.ArtifactWritePlan;
import com.iocextractor.application.artifact.DocumentObservationSelection;
import com.iocextractor.application.artifact.PreparedArtifactRow;
import com.iocextractor.application.artifact.RoutedArtifactCandidate;
import com.iocextractor.application.artifact.policy.ArtifactOccurrenceSelector;
import com.iocextractor.application.artifact.policy.ArtifactWritePolicy;
import com.iocextractor.application.pipeline.payload.AttributedIndicators;
import com.iocextractor.application.pipeline.payload.IndicatorOccurrence;
import com.iocextractor.application.pipeline.payload.PreparedArtifacts;
import com.iocextractor.application.port.out.artifact.ArtifactIdentityResolver;
import com.iocextractor.application.port.out.artifact.ArtifactPreparer;
import com.iocextractor.application.port.out.artifact.DocumentProcessingPlan;
import com.iocextractor.application.port.out.artifact.DocumentProcessingSession;
import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.codes.PipelineDiagnosticCodes;
import com.iocextractor.platform.etl.Envelope;
import com.iocextractor.platform.etl.Stage;
import com.iocextractor.platform.etl.StageId;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Applies the required document plan and its observation policy before the write checkpoint. */
public final class PrepareRoutedArtifactsStage implements Stage<AttributedIndicators, PreparedArtifacts> {
    private final DocumentProcessingPlan processing;
    private final List<ArtifactPreparer> preparers;
    private final ArtifactIdentityResolver identityResolver;
    private final Map<String, ArtifactWritePolicy> policies;
    private final boolean deduplicate;
    private final ArtifactOccurrenceSelector selector = new ArtifactOccurrenceSelector();
    private final DiagnosticFactory diagnostics;

    public PrepareRoutedArtifactsStage(DocumentProcessingPlan processing,
                                       List<ArtifactPreparer> preparers,
                                       ArtifactIdentityResolver identityResolver,
                                       Map<String, ArtifactWritePolicy> policies,
                                       boolean deduplicate) {
        this(processing, preparers, identityResolver, policies, deduplicate,
                new DiagnosticFactory(Clock.systemUTC()));
    }

    public PrepareRoutedArtifactsStage(DocumentProcessingPlan processing,
                                       List<ArtifactPreparer> preparers,
                                       ArtifactIdentityResolver identityResolver,
                                       Map<String, ArtifactWritePolicy> policies,
                                       boolean deduplicate, DiagnosticFactory diagnostics) {
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.processing = Objects.requireNonNull(processing, "processing");
        this.preparers = List.copyOf(preparers);
        this.identityResolver = Objects.requireNonNull(identityResolver, "identityResolver");
        this.policies = Map.copyOf(policies);
        this.deduplicate = deduplicate;
    }

    @Override
    public StageId name() {
        return StageNames.PREPARE_ARTIFACTS;
    }

    @Override
    public Envelope<PreparedArtifacts> process(Envelope<AttributedIndicators> input) {
        Map<String, ArtifactWritePlan> emptyPlans = emptyPlans();
        Grouped grouped;
        try (var session = processing.openSession()) {
            grouped = group(input.payload(), emptyPlans.keySet(), session);
        }
        return input.withPayload(new PreparedArtifacts(grouped.extracted(), grouped.retained(),
                        selectPlans(emptyPlans, grouped.rows())))
                .withDiagnostics(grouped.diagnostics());
    }

    private Map<String, ArtifactWritePlan> emptyPlans() {
        Map<String, ArtifactWritePlan> emptyPlans = new LinkedHashMap<>();
        for (ArtifactPreparer preparer : preparers) {
            ArtifactWritePlan empty = preparer.prepare(List.of()).value();
            if (empty == null || emptyPlans.putIfAbsent(preparer.name(), empty) != null) {
                throw new IllegalStateException("Duplicate or missing artifact preparation: " + preparer.name());
            }
        }
        return emptyPlans;
    }

    private Grouped group(AttributedIndicators input, Set<String> artifacts, DocumentProcessingSession session) {
        boolean retainObservations = processing.observationSelection()
                == DocumentObservationSelection.RETAINED_OBSERVATIONS;
        CandidateAccumulator rows = new CandidateAccumulator(artifacts, retainObservations);
        List<Diagnostic> diagnostics = new ArrayList<>();
        Set<String> seenOriginals = new HashSet<>();
        int retained = 0;
        int ordinal = 0;
        for (var decision : input.outcome().decisions()) {
            boolean keep = !deduplicate || seenOriginals.add(decision.indicator().dedupKey());
            IndicatorOccurrence occurrence = new IndicatorOccurrence(
                    decision.indicator(), decision.rawIndicator().position(), ordinal++,
                    !retainObservations || keep);
            if (keep) {
                retained++;
            } else if (retainObservations) {
                diagnostics.add(this.diagnostics.create(PipelineDiagnosticCodes.ITEM_SKIPPED)
                        .with("item", occurrence.indicator().value())
                        .with("type", occurrence.indicator().type())
                        .with("stage", StageNames.DEDUPLICATE.value())
                        .with("reason", "duplicate within source batch").build());
            }
            var result = session.prepare(occurrence);
            diagnostics.addAll(result.diagnostics());
            for (RoutedArtifactCandidate candidate : Objects.requireNonNull(result.value(), "routed candidates")) {
                rows.add(candidate, keep);
            }
        }
        return new Grouped(ordinal, retained, rows, diagnostics);
    }

    private List<ArtifactWritePlan> selectPlans(Map<String, ArtifactWritePlan> emptyPlans,
                                               CandidateAccumulator candidates) {
        List<ArtifactWritePlan> plans = new ArrayList<>(preparers.size());
        for (ArtifactWritePlan empty : emptyPlans.values()) {
            Objects.requireNonNull(policies.get(empty.artifactName()),
                    "write policy for " + empty.artifactName());
            plans.add(new ArtifactWritePlan(empty.artifactName(), empty.header(),
                    candidates.rowsFor(empty.artifactName()), empty.idSequence()));
        }
        return plans;
    }

    /** Keeps source rows or final-key winners according to the admitted plan and artifact policy. */
    private final class CandidateAccumulator {
        private final Set<String> artifacts;
        private final boolean retainObservations;
        private final Map<String, ArtifactOccurrenceSelector.Accumulator<ArtifactRowKey, PreparedArtifactRow>> groups
                = new LinkedHashMap<>();
        private final Map<String, List<PreparedArtifactRow>> retainedRows = new LinkedHashMap<>();

        private CandidateAccumulator(Set<String> artifacts, boolean retainObservations) {
            this.artifacts = artifacts;
            this.retainObservations = retainObservations;
        }

        private void add(RoutedArtifactCandidate candidate, boolean keep) {
            if (!artifacts.contains(candidate.artifact())) {
                throw new IllegalStateException("Routed candidate targets unknown artifact: " + candidate.artifact());
            }
            ArtifactWritePolicy policy = Objects.requireNonNull(policies.get(candidate.artifact()),
                    "write policy for " + candidate.artifact());
            if (retainObservations && policy.duplicateSelection() == ArtifactWritePolicy.DuplicateSelection.KEEP_FIRST) {
                if (keep) {
                    retainedRows.computeIfAbsent(candidate.artifact(), ignored -> new ArrayList<>()).add(candidate.row());
                }
                return;
            }
            ArtifactRowKey key = identityResolver.keyOf(candidate.artifact(), candidate.row().template())
                    .orElseThrow(() -> new IllegalStateException(
                            "Routed candidate has no final identity: " + candidate.artifact()));
            groups.computeIfAbsent(candidate.artifact(), ignored -> selector.accumulator(policy,
                    row -> row.template().value(policy.selectionColumn()))).add(key, candidate.row());
        }

        private List<PreparedArtifactRow> rowsFor(String artifact) {
            List<PreparedArtifactRow> retained = retainedRows.get(artifact);
            if (retained != null) {
                return retained;
            }
            var accumulator = groups.get(artifact);
            return accumulator == null ? List.of() : accumulator.winners();
        }
    }

    private record Grouped(int extracted, int retained, CandidateAccumulator rows,
                           List<Diagnostic> diagnostics) { }
}
