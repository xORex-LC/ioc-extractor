package com.iocextractor.application.pipeline.stage;

import com.iocextractor.application.artifact.ArtifactRowKey;
import com.iocextractor.application.artifact.ArtifactWritePlan;
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
import com.iocextractor.platform.etl.Envelope;
import com.iocextractor.platform.etl.Stage;
import com.iocextractor.platform.etl.StageId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Resolves configured document candidates by final artifact key before the write checkpoint. */
public final class PrepareRoutedArtifactsStage implements Stage<AttributedIndicators, PreparedArtifacts> {
    private final DocumentProcessingPlan processing;
    private final List<ArtifactPreparer> preparers;
    private final ArtifactIdentityResolver identityResolver;
    private final Map<String, ArtifactWritePolicy> policies;
    private final boolean deduplicate;
    private final ArtifactOccurrenceSelector selector = new ArtifactOccurrenceSelector();

    public PrepareRoutedArtifactsStage(DocumentProcessingPlan processing,
                                       List<ArtifactPreparer> preparers,
                                       ArtifactIdentityResolver identityResolver,
                                       Map<String, ArtifactWritePolicy> policies,
                                       boolean deduplicate) {
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
        Map<String, ArtifactOccurrenceSelector.Accumulator<ArtifactRowKey, PreparedArtifactRow>> groups = new LinkedHashMap<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        Set<String> seenOriginals = new HashSet<>();
        int retained = 0;
        int ordinal = 0;
        for (var decision : input.outcome().decisions()) {
            IndicatorOccurrence occurrence = new IndicatorOccurrence(
                    decision.indicator(), decision.rawIndicator().position(), ordinal++);
            if (!deduplicate || seenOriginals.add(occurrence.indicator().dedupKey())) {
                retained++;
            }
            var result = session.prepare(occurrence);
            diagnostics.addAll(result.diagnostics());
            for (RoutedArtifactCandidate candidate : Objects.requireNonNull(result.value(), "routed candidates")) {
                if (!artifacts.contains(candidate.artifact())) {
                    throw new IllegalStateException("Routed candidate targets unknown artifact: "
                            + candidate.artifact());
                }
                ArtifactRowKey key = identityResolver.keyOf(candidate.artifact(), candidate.row().template())
                        .orElseThrow(() -> new IllegalStateException(
                                "Routed candidate has no final identity: " + candidate.artifact()));
                groups.computeIfAbsent(candidate.artifact(), artifact -> {
                    ArtifactWritePolicy policy = Objects.requireNonNull(policies.get(artifact),
                            "write policy for " + artifact);
                    return selector.accumulator(policy,
                            row -> row.template().value(policy.selectionColumn()));
                }).add(key, candidate.row());
            }
        }
        return new Grouped(ordinal, retained, groups, diagnostics);
    }

    private List<ArtifactWritePlan> selectPlans(Map<String, ArtifactWritePlan> emptyPlans,
            Map<String, ArtifactOccurrenceSelector.Accumulator<ArtifactRowKey, PreparedArtifactRow>> groups) {
        List<ArtifactWritePlan> plans = new ArrayList<>(preparers.size());
        for (ArtifactWritePlan empty : emptyPlans.values()) {
            Objects.requireNonNull(policies.get(empty.artifactName()),
                    "write policy for " + empty.artifactName());
            var accumulator = groups.get(empty.artifactName());
            List<PreparedArtifactRow> rows = accumulator == null ? List.of() : accumulator.winners();
            plans.add(new ArtifactWritePlan(empty.artifactName(), empty.header(), rows, empty.idSequence()));
        }
        return plans;
    }

    private record Grouped(int extracted, int retained,
                           Map<String, ArtifactOccurrenceSelector.Accumulator<ArtifactRowKey, PreparedArtifactRow>> rows,
                           List<Diagnostic> diagnostics) { }
}
