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
import com.iocextractor.application.ingest.admission.DocumentTerminalOutcome;
import com.iocextractor.application.observation.RegisteredObservation;
import com.iocextractor.application.port.out.ingest.SourceLifecycle;
import com.iocextractor.application.port.out.ingest.IngestionLedger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Filesystem adapter for reservation, token claim, stable hashing and claim adoption. */
public final class OrderedDocumentAdmissionHandler {

    private final DocumentAdmissionService admissions;
    private final SourceLifecycle sources;
    private final FileDocumentCandidateEvidenceReader evidenceReader;
    private final FileSourceHasher hasher;
    private final IngestionLedger ledger;

    public OrderedDocumentAdmissionHandler(DocumentAdmissionService admissions,
                                           SourceLifecycle sources,
                                           FileDocumentCandidateEvidenceReader evidenceReader,
                                           FileSourceHasher hasher) {
        this(admissions, sources, evidenceReader, hasher, null);
    }

    /** Creates a handler that reconciles terminal ingestion before touching claimed files. */
    public OrderedDocumentAdmissionHandler(DocumentAdmissionService admissions,
                                           SourceLifecycle sources,
                                           FileDocumentCandidateEvidenceReader evidenceReader,
                                           FileSourceHasher hasher,
                                           IngestionLedger ledger) {
        this.admissions = Objects.requireNonNull(admissions, "admissions");
        this.sources = Objects.requireNonNull(sources, "sources");
        this.evidenceReader = Objects.requireNonNull(evidenceReader, "evidenceReader");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        this.ledger = ledger;
    }

    public AdmittedDocument admit(Path source, ObservationId observationId, Instant detectedAt) {
        Path normalized = Objects.requireNonNull(source, "source").toAbsolutePath().normalize();
        DocumentAdmission existing = admissions.find(observationId).orElse(null);
        if (existing != null && !existing.candidatePath().equals(normalized)) {
            throw new IllegalStateException("Document admission candidate path changed on retry");
        }
        var reservation = existing == null
                ? new DocumentAdmissionReservation(observationId, normalized,
                        evidenceReader.read(normalized),
                        sources.prehashClaimPath(normalized, observationId), detectedAt)
                : new DocumentAdmissionReservation(
                        observationId, existing.candidatePath(), existing.candidateEvidence(),
                        existing.claimPath(), existing.createdAt());
        return advanceToLinked(admissions.admit(reservation));
    }

    public List<AdmittedDocument> recover(int limit) {
        List<AdmittedDocument> recovered = new ArrayList<>();
        for (DocumentAdmission admission : admissions.recover(limit)) {
            if (completeFromIngestionLedger(admission)) {
                continue;
            }
            if (admission.phase() != DocumentAdmissionPhase.TERMINAL) {
                recovered.add(advanceToLinked(admission));
            }
        }
        return List.copyOf(recovered);
    }

    private boolean completeFromIngestionLedger(DocumentAdmission admission) {
        if (ledger == null || admission.phase() != DocumentAdmissionPhase.LINKED) {
            return false;
        }
        return ledger.find(admission.observationId()).map(record -> {
            if (record.status() == IngestionStatus.SOURCE_ARCHIVED) {
                admissions.complete(admission.observationId(), DocumentTerminalOutcome.SUCCEEDED);
                return true;
            }
            if (record.status() == IngestionStatus.FAILED) {
                admissions.complete(admission.observationId(), DocumentTerminalOutcome.REJECTED);
                return true;
            }
            return false;
        }).orElse(false);
    }

    /** Completes the service-journal/dataframe terminal handshake idempotently. */
    public void complete(ObservationId observationId, DocumentTerminalOutcome outcome) {
        admissions.complete(observationId, outcome);
    }

    private AdmittedDocument advanceToLinked(DocumentAdmission initial) {
        DocumentAdmission current = initial;
        ClaimedSource claimed;
        if (current.phase() == DocumentAdmissionPhase.ORDERED) {
            claimed = claimOrRecover(current);
            DocumentCandidateEvidence claimedEvidence = evidenceReader.read(claimed.processingPath());
            if (!current.candidateEvidence().sameObjectAs(claimedEvidence)) {
                throw new IllegalStateException("Document changed before private claim");
            }
            current = admissions.recordClaim(
                    current.observationId(), claimedEvidence);
        } else {
            claimed = claimedSource(current);
        }
        claimed = sources.sealClaim(claimed);
        SourceKey key = stableHash(claimed.processingPath());
        SourceUnit unit = sources.adoptClaim(claimed, key);
        if (current.phase() == DocumentAdmissionPhase.CLAIMED) {
            current = admissions.link(current.observationId(), key);
        } else if (current.phase() == DocumentAdmissionPhase.LINKED
                && !current.sourceKey().orElseThrow().equals(key)) {
            throw new IllegalStateException("Recovered document content key changed");
        }
        if (current.phase() != DocumentAdmissionPhase.LINKED) {
            throw new IllegalStateException("Document admission is not ready for ingestion");
        }
        return new AdmittedDocument(unit, current.registration().orElseThrow());
    }

    private ClaimedSource claimOrRecover(DocumentAdmission admission) {
        boolean claimExists = Files.exists(admission.claimPath());
        boolean candidateExists = Files.exists(admission.candidatePath());
        if (claimExists) {
            return claimedSource(admission);
        }
        if (!candidateExists) {
            throw new IllegalStateException("Document candidate and private claim are both missing");
        }
        return sources.claimBeforeHash(admission.candidatePath(), admission.observationId(),
                admission.createdAt());
    }

    private ClaimedSource claimedSource(DocumentAdmission admission) {
        return new ClaimedSource(admission.observationId(), admission.candidatePath(),
                admission.claimPath(), admission.createdAt());
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
