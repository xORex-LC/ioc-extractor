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
    void sameSizeAndTimestampCannotReuseAReservationForAnotherFileIdentity() {
        var first = new DocumentCandidateEvidence(Optional.of("inode-1"), 42, 100);
        var replacement = new DocumentCandidateEvidence(Optional.of("inode-2"), 42, 100);
        var unavailable = new DocumentCandidateEvidence(Optional.empty(), 42, 100);

        assertThat(first.sameObjectAs(replacement)).isFalse();
        assertThat(first.sameObjectAs(unavailable)).isFalse();
        assertThat(unavailable.sameObjectAs(unavailable)).isFalse();
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
            DocumentAdmission current = values.get(observationId);
            return current != null && current.version() == expectedVersion
                    && values.remove(observationId, current);
        }
    }

    private static final class MemoryRegistrationStore implements ObservationRegistrationStore {
        private final Map<ObservationId, RegisteredObservation> values = new LinkedHashMap<>();
        private final List<ObservationId> terminal = new ArrayList<>();
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
            ObservationAdmissionReference current = values.get(observationId);
            return current != null && current.registrationFinalized()
                    && current.version() == expectedVersion
                    && values.remove(observationId, current);
        }
    }
}
