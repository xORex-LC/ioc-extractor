package com.iocextractor.application.observation;

import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.ingest.SourceKey;
import com.iocextractor.application.ingest.admission.DocumentAdmission;
import com.iocextractor.application.ingest.admission.DocumentAdmissionPhase;
import com.iocextractor.application.ingest.admission.DocumentAdmissionReservation;
import com.iocextractor.application.ingest.admission.DocumentAdmissionService;
import com.iocextractor.application.ingest.admission.DocumentCandidateEvidence;
import com.iocextractor.application.ingest.admission.DocumentTerminalOutcome;
import com.iocextractor.application.port.in.ExtractionCommand;
import com.iocextractor.application.port.out.ingest.DocumentAdmissionJournal;
import com.iocextractor.application.port.out.observation.ObservationAdmissionReferenceStore;
import com.iocextractor.application.port.out.observation.ObservationRegistrationStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObservationAdmissionServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-22T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void documentRecoveryPreservesOneOrderAcrossEveryJournalBoundary() {
        var journal = new MemoryDocumentJournal();
        var registrations = new MemoryRegistrationStore();
        var service = new DocumentAdmissionService(journal, registrations, CLOCK);
        DocumentAdmissionReservation reservation = reservation("document-1");

        DocumentAdmission reserved = journal.reserve(reservation);
        DocumentAdmission ordered = service.recover(10).getFirst();
        DocumentAdmission claimed = service.recordClaim(
                reserved.observationId(), reservation.candidateEvidence());
        DocumentAdmission linked = service.link(reserved.observationId(), new SourceKey("digest"));

        assertThat(ordered.phase()).isEqualTo(DocumentAdmissionPhase.ORDERED);
        assertThat(claimed.registration()).contains(ordered.registration().orElseThrow());
        assertThat(linked.registration()).contains(ordered.registration().orElseThrow());
        assertThat(service.admit(reservation).registration()).isEqualTo(ordered.registration());
        assertThat(registrations.nextOrder).isEqualTo(2);

        DocumentAdmission terminalOnly = linked.terminal(DocumentTerminalOutcome.SUCCEEDED, NOW);
        assertThat(journal.replace(linked, terminalOnly)).isTrue();
        DocumentAdmission recovered = service.recover(10).getFirst();
        assertThat(recovered.registrationFinalized()).isTrue();
        assertThat(registrations.terminal).contains(reserved.observationId());
        assertThat(service.purgeTerminalBefore(NOW.plusSeconds(1), 10)).isOne();
    }

    @Test
    void missingRegistrationFailsClosedInsteadOfAllocatingAnotherOrder() {
        var journal = new MemoryDocumentJournal();
        var registrations = new MemoryRegistrationStore();
        var service = new DocumentAdmissionService(journal, registrations, CLOCK);
        DocumentAdmission ordered = service.admit(reservation("document-2"));
        registrations.values.remove(ordered.observationId());

        assertThatThrownBy(() -> service.recover(10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Missing registered observation");
        assertThat(registrations.nextOrder).isEqualTo(2);
    }

    @Test
    void retryAfterRegistrationBeforeJournalLinkReusesTheAllocatedOrder() {
        var journal = new MemoryDocumentJournal();
        var registrations = new MemoryRegistrationStore();
        var service = new DocumentAdmissionService(journal, registrations, CLOCK);
        DocumentAdmissionReservation reservation = reservation("document-register-crash");
        journal.rejectNextReplace = true;

        assertThatThrownBy(() -> service.admit(reservation))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("changed concurrently");
        DocumentAdmission recovered = service.admit(reservation);

        assertThat(recovered.phase()).isEqualTo(DocumentAdmissionPhase.ORDERED);
        assertThat(recovered.registration().orElseThrow().admissionOrder().value()).isOne();
        assertThat(registrations.nextOrder).isEqualTo(2);
    }

    @Test
    void importAndOneshotUseTheSameOrderAuthorityAndDryRunHasNoSideEffect() {
        var registrations = new MemoryRegistrationStore();
        var references = new MemoryReferenceStore();
        var imports = new ManagedImportObservationAdmission(registrations, references, CLOCK);
        RegisteredObservation imported = imports.register(new ImportDeliveryId("import-1"));
        imports.complete(new ImportDeliveryId("import-1"), "SUCCEEDED");

        AtomicReference<ExtractionCommand> delegated = new AtomicReference<>();
        var decorator = new ObservationOrderedExtractionDecorator(command -> {
            delegated.set(command);
            return null;
        }, registrations);
        decorator.extract(new ExtractionCommand("dry-run", Path.of("source.docx"), true));
        assertThat(registrations.values).hasSize(1);

        decorator.extract(new ExtractionCommand("oneshot-1", Path.of("source.docx"), false));
        RegisteredObservation oneshot = delegated.get().registrationOptional().orElseThrow();
        assertThat(imported.admissionOrder().value()).isEqualTo(1);
        assertThat(oneshot.admissionOrder().value()).isEqualTo(2);
        assertThat(registrations.terminal)
                .containsExactlyInAnyOrder(imported.observationId(), oneshot.observationId());
    }

    @Test
    void managedImportRecoveryCompletesTerminalHandshakeBeforeRetention() {
        var registrations = new MemoryRegistrationStore();
        var references = new MemoryReferenceStore();
        var imports = new ManagedImportObservationAdmission(registrations, references, CLOCK);
        ImportDeliveryId deliveryId = new ImportDeliveryId("import-recovery");
        RegisteredObservation registration = imports.register(deliveryId);
        ObservationAdmissionReference linked = references.find(registration.observationId()).orElseThrow();
        references.replace(linked, linked.terminal("SUCCEEDED", NOW));

        List<ObservationAdmissionReference> recovered = imports.recover(10);

        assertThat(recovered).singleElement().satisfies(reference -> {
            assertThat(reference.registration()).isEqualTo(registration);
            assertThat(reference.registrationFinalized()).isTrue();
        });
        assertThat(registrations.terminal).containsExactly(registration.observationId());
        assertThat(imports.purgeTerminalBefore(NOW.plusSeconds(1), 10)).isOne();
        assertThat(references.values).isEmpty();
        assertThat(registrations.values).isEmpty();
    }

    @Test
    void managedImportFailsClosedOnChangedReferencesAndTerminalOutcome() {
        var registrations = new MemoryRegistrationStore();
        var references = new MemoryReferenceStore();
        var imports = new ManagedImportObservationAdmission(registrations, references, CLOCK);
        ImportDeliveryId mismatchedDelivery = new ImportDeliveryId("import-reference-mismatch");
        ObservationId mismatchedId = new ObservationId(mismatchedDelivery.value());
        references.link(new RegisteredObservation(mismatchedId, "other-namespace",
                new ObservationOrder(99), ObservationOrigin.MANAGED_IMPORT));

        assertThatThrownBy(() -> imports.register(mismatchedDelivery))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Missing registered observation");

        ImportDeliveryId reorderedDelivery = new ImportDeliveryId("import-order-mismatch");
        RegisteredObservation registered = registrations.registerNew(
                new ObservationId(reorderedDelivery.value()), ObservationOrigin.MANAGED_IMPORT);
        references.link(new RegisteredObservation(
                registered.observationId(), registered.namespaceId(),
                new ObservationOrder(registered.admissionOrder().value() + 100), registered.origin()));
        assertThatThrownBy(() -> imports.resume(reorderedDelivery))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reference changed");

        ImportDeliveryId completedDelivery = new ImportDeliveryId("import-completed");
        imports.register(completedDelivery);
        imports.complete(completedDelivery, "SUCCEEDED");
        assertThatThrownBy(() -> imports.complete(completedDelivery, "QUARANTINED"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outcome changed");
        assertThatThrownBy(() -> imports.resume(new ImportDeliveryId("missing-import")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Missing import observation");
        assertThatThrownBy(() -> imports.recover(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit must be positive");
        assertThatThrownBy(() -> imports.purgeTerminalBefore(NOW, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit must be positive");
    }

    @Test
    void managedImportRejectsLegacyDeliveryWithoutOrderedReservation() {
        var registrations = new MemoryRegistrationStore();
        var references = new MemoryReferenceStore();
        references.registrationReserved = false;
        var imports = new ManagedImportObservationAdmission(registrations, references, CLOCK);

        assertThatThrownBy(() -> imports.register(new ImportDeliveryId("legacy-import")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("drain legacy work");
        assertThat(registrations.values).isEmpty();
    }

    @Test
    void oneshotFailureRemainsPrimaryWhenRegistrationFinalizationAlsoFails() {
        var registrations = new MemoryRegistrationStore();
        var extractionFailure = new IllegalStateException("extraction failed");
        var finalizationFailure = new IllegalStateException("finalization failed");
        registrations.terminalFailure = finalizationFailure;
        var decorator = new ObservationOrderedExtractionDecorator(command -> {
            throw extractionFailure;
        }, registrations);

        assertThatThrownBy(() -> decorator.extract(
                new ExtractionCommand("oneshot-failure", Path.of("source.docx"), false)))
                .isSameAs(extractionFailure)
                .satisfies(failure -> assertThat(failure.getSuppressed())
                        .containsExactly(finalizationFailure));
    }

    @Test
    void documentCompletionIsIdempotentAndRejectsAChangedOutcome() {
        var journal = new MemoryDocumentJournal();
        var registrations = new MemoryRegistrationStore();
        var service = new DocumentAdmissionService(journal, registrations, CLOCK);
        DocumentAdmissionReservation reservation = reservation("document-completion");
        service.admit(reservation);
        service.recordClaim(reservation.observationId(), reservation.candidateEvidence());
        service.link(reservation.observationId(), new SourceKey("digest-completion"));

        DocumentAdmission completed = service.complete(
                reservation.observationId(), DocumentTerminalOutcome.SUCCEEDED);
        DocumentAdmission retried = service.complete(
                reservation.observationId(), DocumentTerminalOutcome.SUCCEEDED);

        assertThat(retried).isEqualTo(completed);
        assertThatThrownBy(() -> service.complete(
                reservation.observationId(), DocumentTerminalOutcome.QUARANTINED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outcome changed");
        assertThatThrownBy(() -> service.recover(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit must be positive");
        assertThatThrownBy(() -> service.purgeTerminalBefore(NOW, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit must be positive");
        assertThatThrownBy(() -> service.link(
                new ObservationId("missing-document"), new SourceKey("missing")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Missing document admission");
    }

    @Test
    void sameSizeAndTimestampCannotReuseAReservationForAnotherFileIdentity() {
        var first = new DocumentCandidateEvidence(Optional.of("inode-1"), 42, 100);
        var replacement = new DocumentCandidateEvidence(Optional.of("inode-2"), 42, 100);
        var unavailable = new DocumentCandidateEvidence(Optional.empty(), 42, 100);
        var blankIdentity = new DocumentCandidateEvidence(Optional.of(" "), 42, 100);

        assertThat(first.sameObjectAs(replacement)).isFalse();
        assertThat(first.sameObjectAs(unavailable)).isFalse();
        assertThat(unavailable.sameObjectAs(unavailable)).isFalse();
        assertThat(blankIdentity.fileKey()).isEmpty();
        assertThat(first.sameObjectAs(new DocumentCandidateEvidence(Optional.of("inode-1"), 41, 100)))
                .isFalse();
        assertThat(first.sameObjectAs(new DocumentCandidateEvidence(Optional.of("inode-1"), 42, 99)))
                .isFalse();
        assertThatThrownBy(() -> new DocumentCandidateEvidence(Optional.empty(), -1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nonnegative");
        assertThatThrownBy(() -> new DocumentCandidateEvidence(Optional.empty(), 0, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nonnegative");
    }

    @Test
    void observationCoordinatesRejectInvalidBoundaries() {
        ObservationId observationId = new ObservationId("invalid-coordinate");

        assertThatThrownBy(() -> new ObservationOrder(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be positive");
        assertThatThrownBy(() -> new OccurrencePosition(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be nonnegative");
        assertThatThrownBy(() -> new RegisteredObservation(
                observationId, null, new ObservationOrder(1), ObservationOrigin.DOCUMENT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires a namespace");
        assertThatThrownBy(() -> new RegisteredObservation(
                observationId, " ", new ObservationOrder(1), ObservationOrigin.DOCUMENT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires a namespace");
    }

    @Test
    void registrationStatusRejectsImpossibleCountsAndNormalizesMissingAge() {
        assertThat(new ObservationRegistrationStatus(2, 1, null).oldestPendingOneshot())
                .isEmpty();
        assertThat(new ObservationRegistrationStatus(1, 1, Optional.of(NOW)).oldestPendingOneshot())
                .contains(NOW);

        assertThatThrownBy(() -> new ObservationRegistrationStatus(-1, 0, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("counts are inconsistent");
        assertThatThrownBy(() -> new ObservationRegistrationStatus(0, -1, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("counts are inconsistent");
        assertThatThrownBy(() -> new ObservationRegistrationStatus(1, 2, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("counts are inconsistent");
    }

    @Test
    void observationReferenceEnforcesItsTerminalStateMachine() {
        RegisteredObservation registration = new RegisteredObservation(
                new ObservationId("reference-state"), "dataframe",
                new ObservationOrder(1), ObservationOrigin.MANAGED_IMPORT);
        var active = new ObservationAdmissionReference(
                registration, 0, Optional.empty(), false, NOW, NOW);
        var normalizedBlank = new ObservationAdmissionReference(
                registration, 0, Optional.of(" "), false, NOW, NOW);

        assertThat(normalizedBlank.terminalOutcome()).isEmpty();

        assertThatThrownBy(() -> new ObservationAdmissionReference(
                registration, -1, Optional.empty(), false, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version or timestamps");
        assertThatThrownBy(() -> new ObservationAdmissionReference(
                registration, 0, Optional.empty(), false, NOW, NOW.minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version or timestamps");
        assertThatThrownBy(() -> new ObservationAdmissionReference(
                registration, 1, Optional.empty(), true, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be terminal");
        assertThatThrownBy(() -> active.terminal(" ", NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
        assertThatThrownBy(() -> active.finalized(NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is not terminal");
        assertThatThrownBy(() -> active.terminal("SUCCEEDED", NOW)
                .terminal("FAILED", NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outcome changed");
        assertThat(active.terminal("SUCCEEDED", NOW).terminal("SUCCEEDED", NOW).terminalOutcome())
                .contains("SUCCEEDED");
    }

    @Test
    void documentAdmissionRejectsOutOfOrderAndContradictoryTransitions() {
        DocumentAdmissionReservation reservation = reservation("document-state");
        DocumentAdmission reserved = DocumentAdmission.reserved(reservation);
        RegisteredObservation wrongRegistration = new RegisteredObservation(
                new ObservationId("another-document"), "dataframe",
                new ObservationOrder(1), ObservationOrigin.DOCUMENT);

        assertThatThrownBy(() -> new DocumentAdmission(
                reserved.observationId(), reserved.candidatePath(), reserved.candidateEvidence(),
                reserved.claimPath(), reserved.claimedEvidence(), reserved.phase(), -1,
                reserved.registration(), reserved.sourceKey(), reserved.terminalOutcome(),
                reserved.registrationFinalized(), reserved.createdAt(), reserved.updatedAt()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version or timestamps");
        assertThatThrownBy(() -> new DocumentAdmission(
                reserved.observationId(), reserved.candidatePath(), reserved.candidateEvidence(),
                reserved.claimPath(), reserved.claimedEvidence(), reserved.phase(), 0,
                reserved.registration(), reserved.sourceKey(), reserved.terminalOutcome(),
                reserved.registrationFinalized(), NOW, NOW.minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version or timestamps");
        assertThatThrownBy(() -> reserved.ordered(wrongRegistration, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("another document occurrence");
        assertThatThrownBy(() -> reserved.claimed(reservation.candidateEvidence(), NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Expected document admission phase ORDERED");

        RegisteredObservation registration = new RegisteredObservation(
                reservation.observationId(), "dataframe",
                new ObservationOrder(1), ObservationOrigin.DOCUMENT);
        DocumentAdmission ordered = reserved.ordered(registration, NOW);
        DocumentCandidateEvidence replacement = new DocumentCandidateEvidence(
                Optional.of("replacement-inode"), 12, 20);

        assertThatThrownBy(() -> ordered.claimed(replacement, NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("differs from reservation");
        assertThatThrownBy(() -> ordered.terminal(DocumentTerminalOutcome.SUCCEEDED, NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be linked");
    }

    @Test
    void documentAdmissionRejectsEveryInconsistentPersistedPhase() {
        DocumentAdmissionReservation reservation = reservation("document-invalid-phase");
        RegisteredObservation registration = new RegisteredObservation(
                reservation.observationId(), "dataframe",
                new ObservationOrder(1), ObservationOrigin.DOCUMENT);
        Optional<DocumentCandidateEvidence> evidence = Optional.of(reservation.candidateEvidence());
        Optional<SourceKey> sourceKey = Optional.of(new SourceKey("digest-invalid"));

        assertInvalidAdmission(reservation, DocumentAdmissionPhase.RESERVED,
                Optional.of(registration), Optional.empty(), Optional.empty(), Optional.empty(), false,
                "requires one registration");
        assertInvalidAdmission(reservation, DocumentAdmissionPhase.ORDERED,
                Optional.of(registration), evidence, Optional.empty(), Optional.empty(), false,
                "requires claimed evidence");
        assertInvalidAdmission(reservation, DocumentAdmissionPhase.CLAIMED,
                Optional.of(registration), evidence, sourceKey, Optional.empty(), false,
                "requires a source key");
        assertInvalidAdmission(reservation, DocumentAdmissionPhase.LINKED,
                Optional.of(registration), evidence, sourceKey,
                Optional.of(DocumentTerminalOutcome.SUCCEEDED), false,
                "Terminal outcome");
        assertInvalidAdmission(reservation, DocumentAdmissionPhase.LINKED,
                Optional.of(registration), evidence, sourceKey, Optional.empty(), true,
                "Only terminal admission");
        assertThatThrownBy(() -> new DocumentAdmissionReservation(
                reservation.observationId(), reservation.candidatePath(), reservation.candidateEvidence(),
                reservation.candidatePath(), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must differ");
    }

    @Test
    void admissionRecoveryAcceptsDurableCasOutcomeAndSkipsIncompleteRetention() {
        var journal = new MemoryDocumentJournal();
        var registrations = new MemoryRegistrationStore();
        var documents = new DocumentAdmissionService(journal, registrations, CLOCK);
        DocumentAdmissionReservation reservation = reservation("document-cas-recovery");
        journal.persistUpdateBeforeReject = true;

        DocumentAdmission ordered = documents.admit(reservation);
        documents.recordClaim(reservation.observationId(), reservation.candidateEvidence());
        assertThat(documents.recover(10)).singleElement()
                .satisfies(value -> assertThat(value.phase()).isEqualTo(DocumentAdmissionPhase.CLAIMED));
        DocumentAdmission linked = documents.link(reservation.observationId(), new SourceKey("digest-cas"));
        DocumentAdmission terminalOnly = linked.terminal(DocumentTerminalOutcome.SUCCEEDED, NOW);
        assertThat(journal.replace(linked, terminalOnly)).isTrue();
        assertThat(documents.purgeTerminalBefore(NOW.plusSeconds(1), 10)).isZero();
        assertThat(documents.recover(10)).singleElement()
                .satisfies(value -> assertThat(value.registrationFinalized()).isTrue());
        journal.rejectNextPurge = true;
        assertThat(documents.purgeTerminalBefore(NOW.plusSeconds(1), 10)).isZero();
        assertThat(ordered.registration()).isPresent();

        var references = new MemoryReferenceStore();
        var imports = new ManagedImportObservationAdmission(registrations, references, CLOCK);
        ImportDeliveryId deliveryId = new ImportDeliveryId("import-cas-recovery");
        imports.register(deliveryId);
        references.persistUpdateBeforeReject = true;
        imports.complete(deliveryId, "SUCCEEDED");
        imports.complete(deliveryId, "SUCCEEDED");

        ImportDeliveryId pendingId = new ImportDeliveryId("import-pending-recovery");
        imports.register(pendingId);
        assertThat(imports.recover(10))
                .anySatisfy(reference -> assertThat(reference.registration().observationId().value())
                        .isEqualTo(pendingId.value()));

        references.rejectNextPurge = true;
        assertThat(imports.purgeTerminalBefore(NOW.plusSeconds(1), 10)).isZero();
    }

    private static void assertInvalidAdmission(
            DocumentAdmissionReservation reservation,
            DocumentAdmissionPhase phase,
            Optional<RegisteredObservation> registration,
            Optional<DocumentCandidateEvidence> claimedEvidence,
            Optional<SourceKey> sourceKey,
            Optional<DocumentTerminalOutcome> terminalOutcome,
            boolean registrationFinalized,
            String message) {
        assertThatThrownBy(() -> new DocumentAdmission(
                reservation.observationId(), reservation.candidatePath(), reservation.candidateEvidence(),
                reservation.claimPath(), claimedEvidence, phase, 0, registration, sourceKey,
                terminalOutcome, registrationFinalized, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(message);
    }

    private static DocumentAdmissionReservation reservation(String id) {
        return new DocumentAdmissionReservation(new ObservationId(id),
                Path.of("inbox", id + ".docx"),
                new DocumentCandidateEvidence(Optional.of("inode-" + id), 12, 20),
                Path.of("processing", id + ".pending"), NOW);
    }

    private static final class MemoryDocumentJournal implements DocumentAdmissionJournal {
        private final Map<ObservationId, DocumentAdmission> values = new LinkedHashMap<>();
        private boolean rejectNextReplace;
        private boolean persistUpdateBeforeReject;
        private boolean rejectNextPurge;

        @Override
        public DocumentAdmission reserve(DocumentAdmissionReservation reservation) {
            return values.computeIfAbsent(reservation.observationId(), ignored ->
                    DocumentAdmission.reserved(reservation));
        }

        @Override
        public Optional<DocumentAdmission> find(ObservationId observationId) {
            return Optional.ofNullable(values.get(observationId));
        }

        @Override
        public boolean replace(DocumentAdmission expected, DocumentAdmission updated) {
            if (persistUpdateBeforeReject) {
                persistUpdateBeforeReject = false;
                values.replace(expected.observationId(), expected, updated);
                return false;
            }
            if (rejectNextReplace) {
                rejectNextReplace = false;
                return false;
            }
            return values.replace(expected.observationId(), expected, updated);
        }

        @Override
        public List<DocumentAdmission> findRecoverable(int limit) {
            return values.values().stream()
                    .filter(value -> value.phase() != DocumentAdmissionPhase.TERMINAL
                            || !value.registrationFinalized())
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<DocumentAdmission> findTerminalBefore(Instant cutoff, int limit) {
            return values.values().stream()
                    .filter(value -> value.phase() == DocumentAdmissionPhase.TERMINAL)
                    .filter(value -> value.updatedAt().isBefore(cutoff))
                    .limit(limit)
                    .toList();
        }

        @Override
        public boolean purgeTerminal(ObservationId observationId, long expectedVersion) {
            if (rejectNextPurge) {
                rejectNextPurge = false;
                return false;
            }
            DocumentAdmission current = values.get(observationId);
            return current != null && current.version() == expectedVersion
                    && values.remove(observationId, current);
        }
    }

    private static final class MemoryRegistrationStore implements ObservationRegistrationStore {
        private final Map<ObservationId, RegisteredObservation> values = new LinkedHashMap<>();
        private final List<ObservationId> terminal = new ArrayList<>();
        private RuntimeException terminalFailure;
        private long nextOrder = 1;

        @Override
        public RegisteredObservation registerNew(ObservationId id, ObservationOrigin origin) {
            RegisteredObservation existing = values.get(id);
            if (existing != null) {
                if (existing.origin() != origin) {
                    throw new IllegalStateException("Occurrence origin changed");
                }
                return existing;
            }
            RegisteredObservation created = new RegisteredObservation(
                    id, "0123456789abcdef0123456789abcdef", new ObservationOrder(nextOrder++), origin);
            values.put(id, created);
            return created;
        }

        @Override
        public RegisteredObservation resume(ObservationId id, String expectedNamespace) {
            RegisteredObservation value = values.get(id);
            if (value == null) {
                throw new IllegalStateException("Missing registered observation on recovery");
            }
            if (!value.namespaceId().equals(expectedNamespace)) {
                throw new IllegalStateException("Namespace changed");
            }
            return value;
        }

        @Override
        public void markTerminal(ObservationId id, String expectedNamespace) {
            resume(id, expectedNamespace);
            if (terminalFailure != null) {
                throw terminalFailure;
            }
            if (!terminal.contains(id)) {
                terminal.add(id);
            }
        }

        @Override
        public boolean purgeTerminal(RegisteredObservation registration) {
            return terminal.contains(registration.observationId())
                    && values.remove(registration.observationId(), registration);
        }
    }

    private static final class MemoryReferenceStore implements ObservationAdmissionReferenceStore {
        private final Map<ObservationId, ObservationAdmissionReference> values = new LinkedHashMap<>();
        private boolean registrationReserved = true;
        private boolean persistUpdateBeforeReject;
        private boolean rejectNextPurge;

        @Override
        public boolean isRegistrationReserved(ObservationId observationId) {
            return registrationReserved;
        }

        @Override
        public ObservationAdmissionReference link(RegisteredObservation registration) {
            return values.computeIfAbsent(registration.observationId(), ignored ->
                    new ObservationAdmissionReference(registration, 0, Optional.empty(), false, NOW, NOW));
        }

        @Override
        public Optional<ObservationAdmissionReference> find(ObservationId observationId) {
            return Optional.ofNullable(values.get(observationId));
        }

        @Override
        public boolean replace(ObservationAdmissionReference expected,
                               ObservationAdmissionReference updated) {
            if (persistUpdateBeforeReject) {
                persistUpdateBeforeReject = false;
                values.replace(expected.registration().observationId(), expected, updated);
                return false;
            }
            return values.replace(expected.registration().observationId(), expected, updated);
        }

        @Override
        public List<ObservationAdmissionReference> findUnfinalized(int limit) {
            return values.values().stream()
                    .filter(value -> !value.registrationFinalized())
                    .sorted(Comparator.comparing(ObservationAdmissionReference::createdAt))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<ObservationAdmissionReference> findFinalizedBefore(Instant cutoff, int limit) {
            return values.values().stream()
                    .filter(ObservationAdmissionReference::registrationFinalized)
                    .filter(value -> value.updatedAt().isBefore(cutoff))
                    .limit(limit)
                    .toList();
        }

        @Override
        public boolean purgeFinalized(ObservationId observationId, long expectedVersion) {
            if (rejectNextPurge) {
                rejectNextPurge = false;
                return false;
            }
            ObservationAdmissionReference current = values.get(observationId);
            return current != null && current.registrationFinalized()
                    && current.version() == expectedVersion
                    && values.remove(observationId, current);
        }
    }
}
