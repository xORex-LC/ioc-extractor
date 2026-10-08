package com.iocextractor.application;

import com.iocextractor.application.artifact.*;
import com.iocextractor.application.artifact.policy.*;
import com.iocextractor.application.pipeline.payload.*;
import com.iocextractor.application.pipeline.stage.PrepareRoutedArtifactsStage;
import com.iocextractor.application.port.out.artifact.*;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.platform.etl.*;
import java.util.*;

/** Small in-memory oracle used only in framework-free application tests. */
public final class TestDocumentWorkspace implements DocumentPreparationWorkspace {
    private final ArtifactIdentityResolver identities;
    private final Map<String, ArtifactWritePolicy> policies;
    private final Set<String> originals = new HashSet<>();
    private final Map<String, List<PreparedArtifactRow>> retained = new HashMap<>();
    private final Map<String, ArtifactOccurrenceSelector.Accumulator<ArtifactRowKey, PreparedArtifactRow>> selected = new HashMap<>();
    private TestDocumentWorkspace(ArtifactIdentityResolver identities, Map<String, ArtifactWritePolicy> policies) {
        this.identities = identities; this.policies = policies;
    }
    public java.nio.file.Path source() { return java.nio.file.Path.of("unused.docx"); }
    public DocumentSourceWorkspace sourceWorkspace() { return new TestDocumentSourceWorkspace(); }
    public void discard() { }
    public void beginPromotion() { }
    public boolean promotionStarted() { return false; }
    public void close() { }
    public boolean firstOriginal(String key) { return originals.add(key); }
    public void append(RoutedArtifactCandidate candidate, boolean eligible, boolean retainObservations) {
        var policy = policies.get(candidate.artifact());
        if (retainObservations && policy.duplicateSelection() == ArtifactWritePolicy.DuplicateSelection.KEEP_FIRST) {
            if (eligible) { retained.computeIfAbsent(candidate.artifact(), ignored -> new ArrayList<>()).add(candidate.row()); }
        } else {
            var key = identities.keyOf(candidate.artifact(), candidate.row().template()).orElseThrow();
            selected.computeIfAbsent(candidate.artifact(), ignored -> new ArtifactOccurrenceSelector().accumulator(
                    policy, row -> row.template().value(policy.selectionColumn()))).add(key, candidate.row());
        }
    }
    public List<ArtifactWritePlan> seal(List<ArtifactWritePlan> descriptors,
            com.iocextractor.application.artifact.DocumentPreparationSummary summary) {
        return descriptors.stream().map(descriptor -> new ArtifactWritePlan(descriptor.artifactName(),
                descriptor.header(), retained.containsKey(descriptor.artifactName()) ? retained.get(descriptor.artifactName())
                : selected.containsKey(descriptor.artifactName()) ? selected.get(descriptor.artifactName()).winners() : List.of(),
                descriptor.idSequence())).toList();
    }
    public static DocumentPreparationWorkspaceFactory factory(ArtifactIdentityResolver identities) {
        return (command, policies) -> new TestDocumentWorkspace(identities, policies);
    }
    public static Stage<AttributedIndicators, PreparedArtifacts> stage(DocumentProcessingPlan processing,
            List<ArtifactPreparer> preparers, ArtifactIdentityResolver identities,
            Map<String, ArtifactWritePolicy> policies, boolean deduplicate) {
        return stage(processing, preparers, identities, policies, deduplicate, new DiagnosticFactory(java.time.Clock.systemUTC()), 10000);
    }
    public static Stage<AttributedIndicators, PreparedArtifacts> stage(DocumentProcessingPlan processing,
            List<ArtifactPreparer> preparers, ArtifactIdentityResolver identities,
            Map<String, ArtifactWritePolicy> policies, boolean deduplicate, DiagnosticFactory diagnostics, int limit) {
        var delegate = new PrepareRoutedArtifactsStage(processing, preparers, deduplicate, diagnostics, limit);
        return new Stage<>() {
            public StageId name() { return delegate.name(); }
            public Envelope<PreparedArtifacts> process(Envelope<AttributedIndicators> input) {
                var output = delegate.process(input.withMetaAttribute(com.iocextractor.application.pipeline.PipelineMetaAttributes.DOCUMENT_PREPARATION_WORKSPACE,
                        new TestDocumentWorkspace(identities, policies)));
                return new Envelope<>(output.payload(), input.meta(), output.diagnostics(), output.diagnosticSummary());
            }
        };
    }
}
