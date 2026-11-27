package com.iocextractor.adapter.in.ingest;

import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.ingest.ClaimedSource;
import com.iocextractor.application.ingest.SourceKey;
import com.iocextractor.application.ingest.SourceUnit;
import com.iocextractor.application.ingest.IngestionStatus;
import com.iocextractor.application.ingest.admission.DocumentAdmission;
import com.iocextractor.application.ingest.admission.DocumentAdmissionPhase;
import com.iocextractor.application.ingest.admission.DocumentAdmissionReservation;
import com.iocextractor.application.ingest.admission.DocumentAdmissionService;
import com.iocextractor.application.ingest.admission.DocumentCandidateEvidence;
import com.iocextractor.application.ingest.admission.DocumentRejection;
import com.iocextractor.application.ingest.admission.DocumentExecutionState.Failure;
import com.iocextractor.application.ingest.admission.DocumentTerminalOutcome;
import com.iocextractor.application.observation.RegisteredObservation;
import com.iocextractor.application.port.out.ingest.IngestionLedger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/** Filesystem adapter for reservation, token claim, stable hashing and claim adoption. */
public final class OrderedDocumentAdmissionHandler {

    private final DocumentAdmissionService admissions;
    private final FileSystemSourceLifecycle sources;
    private final FileDocumentCandidateEvidenceReader evidenceReader;
    private final FileSourceHasher hasher;
    private final IngestionLedger ledger;

    /** Creates a handler that reconciles terminal ingestion before touching claimed files. */
    public OrderedDocumentAdmissionHandler(DocumentAdmissionService admissions,
                                           FileSystemSourceLifecycle sources,
                                           FileDocumentCandidateEvidenceReader evidenceReader,
                                           FileSourceHasher hasher,
                                           IngestionLedger ledger) {
        this.admissions = Objects.requireNonNull(admissions, "admissions");
        this.sources = Objects.requireNonNull(sources, "sources");
        this.evidenceReader = Objects.requireNonNull(evidenceReader, "evidenceReader");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
    }

    public AdmittedDocument admit(Path source, ObservationId observationId, Instant detectedAt) {
        return finish(claim(source, observationId, detectedAt).observationId());
    }

    /** Short intake boundary: durable order and atomic ownership, without snapshot copy or hashing. */
    public DocumentAdmission claim(Path source, ObservationId observationId, Instant detectedAt) {
        var evidence = admissions.find(observationId).map(DocumentAdmission::candidateEvidence)
                .orElseGet(() -> evidenceReader.read(source));
        return claim(source, observationId, detectedAt, evidence);
    }

    /** Uses the same metadata evidence that passed the driving adapter's byte admission. */
    public DocumentAdmission claim(Path source, ObservationId observationId, Instant detectedAt,
                                   DocumentCandidateEvidence evidence) {
        Path normalized = Objects.requireNonNull(source, "source").toAbsolutePath().normalize();
        DocumentAdmission existing = admissions.find(observationId).orElse(null);
        if (existing != null && !existing.candidatePath().equals(normalized)) {
            throw new IllegalStateException("Document admission candidate path changed on retry");
        }
        var reservation = existing == null
                ? new DocumentAdmissionReservation(observationId, normalized,
                        evidence,
                        sources.prehashClaimPath(normalized, observationId), detectedAt)
                : new DocumentAdmissionReservation(
                        observationId, existing.candidatePath(), existing.candidateEvidence(),
                        existing.claimPath(), existing.createdAt());
        return acquireClaim(admissions.admit(reservation));
    }

    /** Resumes just the ownership step, including moves made before their journal update. */
    DocumentAdmission ensureClaimed(ObservationId id) {
        DocumentAdmission current = required(id);
        return switch (current.phase()) {
            case RESERVED -> claim(current.candidatePath(), id, current.createdAt());
            case ORDERED -> acquireClaim(current);
            default -> current;
        };
    }

    public AdmittedDocument finish(ObservationId id) {
        DocumentAdmission current = ensureClaimed(id);
        if (current.rejection().isPresent() || current.phase() == DocumentAdmissionPhase.TERMINAL) {
            throw new IllegalStateException("Document is unavailable for preparation");
        }
        Path claimPath = checkedClaim(current);
        ClaimedSource claimed = new ClaimedSource(id, current.candidatePath(), claimPath, current.createdAt());
        Path sealedPath = DocumentRejectionFiles.sealed(claimPath);
        if (DocumentRejectionFiles.present(sealedPath)) {
            requireSealedEvidence(current, sealedPath, evidenceReader.read(sealedPath));
        }
        if (DocumentRejectionFiles.present(claimPath)
                && !current.candidateEvidence().sameObjectAs(evidenceReader.read(claimPath))) {
            throw new IllegalStateException("Document claim evidence changed before sealing");
        }
        claimed = sources.sealClaim(claimed, current.candidateEvidence().size());
        var snapshot = evidenceReader.read(claimed.processingPath());
        if (snapshot.size() != current.candidateEvidence().size()) {
            throw new IllegalStateException("Document size changed after bounded admission");
        }
        if (current.phase() == DocumentAdmissionPhase.CLAIMED
                && !current.claimedEvidence().orElseThrow().equals(snapshot)) {
            current = admissions.recordSnapshot(current, snapshot);
        }
        SourceKey key = stableHash(claimed.processingPath());
        SourceUnit unit = sources.adoptClaim(claimed, key);
        if (current.phase() == DocumentAdmissionPhase.CLAIMED) {
            current = admissions.link(current.observationId(), key);
        }
        if (!current.sourceKey().orElseThrow().equals(key)) {
            throw new IllegalStateException("Document admission is not ready for ingestion");
        }
        return new AdmittedDocument(unit, current.registration().orElseThrow());
    }

    AdmittedDocument linkedDocument(DocumentAdmission admission) {
        var id = admission.observationId();
        Path sealed = DocumentRejectionFiles.sealed(checkedClaim(admission));
        var source = new SourceUnit(id, admission.sourceKey().orElseThrow(), admission.candidatePath(),
                sealed, admission.createdAt());
        return new AdmittedDocument(source, admission.registration().orElseThrow());
    }

    public List<DocumentAdmission> pending(int limit) {
        return pending(limit, id -> false);
    }

    List<DocumentAdmission> pending(int limit, Predicate<ObservationId> hasPromotionPlan) {
        return admissions.pending(limit).stream().filter(value -> {
            if (value.phase() == DocumentAdmissionPhase.TERMINAL) {
                if (hasPromotionPlan.test(value.observationId())) {
                    return true;
                }
                admissions.complete(value.observationId(), value.terminalOutcome().orElseThrow());
                return false;
            }
            return !completeArchived(value, hasPromotionPlan);
        }).toList();
    }

    /** Read-only bounded metadata for health; reconciliation remains an execution responsibility. */
    public List<DocumentAdmission> inspectPending(int limit) { return admissions.pending(limit); }

    public DocumentAdmission beginExecution(ObservationId id) { return admissions.beginExecution(id); }

    public void retryExecution(ObservationId id, Instant retryAfter, String failure) {
        admissions.retryExecution(id, retryAfter, failure);
    }

    /** Pins done files and failed-file bundles through terminal registration. */
    public boolean pinsRejectionLeaf(Path path) {
        return sources.rejectionOwner(path).flatMap(admissions::find)
                .filter(value -> value.rejection().isPresent() && !value.registrationFinalized())
                .isPresent() || sources.archiveOwner(path).flatMap(admissions::find)
                .filter(value -> !value.registrationFinalized())
                .isPresent();
    }

    DocumentAdmission required(ObservationId id) { return admissions.find(id).orElseThrow(); }

    /**
     * The ordered promotion path persists a source ledger claim before canonical effects.
     * A legacy failed row with no recorded disposition also cannot establish their absence.
     */
    boolean promotionUncertain(DocumentAdmission admission) {
        return ledger.find(admission.observationId()).isPresent();
    }

    private boolean completeArchived(DocumentAdmission admission, Predicate<ObservationId> hasPromotionPlan) {
        if (admission.phase() != DocumentAdmissionPhase.LINKED || admission.rejection().isPresent()) {
            return false;
        }
        return ledger.find(admission.observationId())
                .filter(record -> record.status() == IngestionStatus.SOURCE_ARCHIVED
                        && admission.sourceKey().orElseThrow().equals(record.key())
                        && !hasPromotionPlan.test(admission.observationId()))
                .map(record -> {
                    admissions.complete(admission.observationId(), DocumentTerminalOutcome.SUCCEEDED);
                    return true;
                }).orElse(false);
    }

    /** Saves the ownership proof and destination before moving the exact private file. */
    DocumentAdmission beginRejection(DocumentAdmission current, DocumentRejection.Code code) {
        if (promotionUncertain(current)) {
            throw new IllegalStateException("Canonical promotion outcome requires reconciliation");
        }
        Path claim = checkedClaim(current);
        Path sealed = DocumentRejectionFiles.sealed(claim);
        Path source;
        DocumentCandidateEvidence evidence;
        if (DocumentRejectionFiles.present(sealed)) {
            source = sealed;
            evidence = evidenceReader.read(source);
            requireSealedEvidence(current, source, evidence);
        } else {
            if (current.sourceKey().isPresent()) {
                throw new IllegalStateException("Linked document's private snapshot is missing");
            }
            source = claim;
            evidence = evidenceReader.read(source);
            requireClaimIdentity(current, evidence);
        }
        return admissions.beginRejection(current, new DocumentRejection(
                source, failedTarget(current.observationId()), evidence, code));
    }

    /** Reconciles file, sidecar, keyed ledger and terminal registration in that order. */
    void finishRejection(DocumentAdmission admission) {
        DocumentRejection intent = admission.rejection().orElseThrow();
        var source = admission.sourceKey().map(key -> new SourceUnit(admission.observationId(), key,
                admission.candidatePath(), intent.source(), admission.createdAt()));
        ledger.find(admission.observationId()).ifPresent(record -> {
            if (source.isEmpty() || !record.matchesRejection(source.orElseThrow(), intent.target(), intent.code().name())) {
                throw new IllegalStateException("Canonical promotion outcome conflicts with document rejection");
            }
        });
        sources.rejections().finish(admission, checkedClaim(admission),
                failedTarget(admission.observationId()), evidenceReader);
        if (source.isPresent()
                && !ledger.recordRejected(source.orElseThrow(), intent.target(), intent.code().name()).completed()) {
            throw new IllegalStateException("Ingestion ledger rejected the document disposition");
        }
        admissions.complete(admission.observationId(), DocumentTerminalOutcome.REJECTED);
    }

    private Path failedTarget(ObservationId id) {
        return sources.rejections().target(sources.observationToken(id));
    }

    private DocumentAdmission acquireClaim(DocumentAdmission current) {
        if (current.phase() != DocumentAdmissionPhase.ORDERED) {
            return current;
        }
        Path claim = checkedClaim(current);
        if (!DocumentRejectionFiles.present(claim)) {
            if (!current.candidateEvidence().sameObjectAs(evidenceReader.read(current.candidatePath()))) {
                throw new DocumentOwnershipException(Failure.CANDIDATE_REPLACED);
            }
            sources.claimBeforeHash(current.candidatePath(), current.observationId(), current.createdAt());
        }
        return admissions.recordClaim(current.observationId(), evidenceReader.read(claim));
    }

    private Path checkedClaim(DocumentAdmission admission) {
        Path claim = sources.prehashClaimPath(admission.candidatePath(), admission.observationId());
        if (!claim.equals(admission.claimPath())) {
            throw new IllegalStateException("Document claim path differs from configured ownership");
        }
        return claim;
    }

    private void requireClaimIdentity(DocumentAdmission admission, DocumentCandidateEvidence evidence) {
        if (!admission.candidateEvidence().sameFileAs(evidence)) {
            throw new IllegalStateException("Document claim identity changed");
        }
    }

    private void requireSealedEvidence(DocumentAdmission admission, Path sealed, DocumentCandidateEvidence evidence) {
        var claimed = admission.claimedEvidence().orElseThrow();
        // Older journals, and a crash just after sealing, can still hold the original inode.
        if (admission.candidateEvidence().sameFileAs(claimed)) {
            var original = admission.candidateEvidence();
            if (evidence.fileKey().isEmpty() || evidence.size() != original.size()
                    || evidence.modifiedAtNanos() != original.modifiedAtNanos()
                    || admission.sourceKey().filter(key -> !key.equals(stableHash(sealed))).isPresent()) {
                throw new IllegalStateException("Document snapshot evidence is inconsistent");
            }
        } else if (!claimed.sameObjectAs(evidence)) {
            throw new IllegalStateException("Document snapshot identity changed");
        }
    }

    private SourceKey stableHash(Path claimedPath) {
        var before = evidenceReader.read(claimedPath);
        SourceKey key = hasher.sha256(claimedPath);
        var after = evidenceReader.read(claimedPath);
        if (!before.sameObjectAs(after)) {
            throw new IllegalStateException("Document changed while hashing");
        }
        return key;
    }

    public record AdmittedDocument(SourceUnit source, RegisteredObservation registration) {
        public AdmittedDocument {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(registration, "registration");
        }
    }
}
