package com.iocextractor.application.ingest.admission;

import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.ingest.SourceKey;
import com.iocextractor.application.observation.ObservationOrder;
import com.iocextractor.application.observation.ObservationOrigin;
import com.iocextractor.application.observation.RegisteredObservation;
import com.iocextractor.application.port.out.ingest.DocumentAdmissionJournal;
import com.iocextractor.application.port.out.observation.ObservationRegistrationStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(20)
class DocumentAdmissionCompletionTest {
    private static final Instant NOW = Instant.parse("2026-10-13T00:00:00Z");
    private static final ObservationId ID = new ObservationId("concurrent-document");
    private static final RegisteredObservation REGISTRATION = new RegisteredObservation(
            ID, "namespace", new ObservationOrder(1), ObservationOrigin.DOCUMENT);

    @Test
    void concurrentCompletionConvergesAcrossBothTerminalWritesWithDifferentUtc() throws Exception {
        var journal = new Journal(linked(REGISTRATION, new SourceKey("digest")));
        var registrations = new Registrations();
        journal.terminalBarrier = new CyclicBarrier(2);
        journal.finalizationBarrier = new CyclicBarrier(2);
        var first = service(journal, registrations, 1);
        var second = service(journal, registrations, 2);

        var replies = concurrent(() -> first.complete(ID, DocumentTerminalOutcome.SUCCEEDED),
                () -> second.complete(ID, DocumentTerminalOutcome.SUCCEEDED));

        assertThat(replies).allSatisfy(reply -> {
            assertThat(reply.registrationFinalized()).isTrue();
            assertThat(reply.terminalOutcome()).contains(DocumentTerminalOutcome.SUCCEEDED);
            assertThat(reply.registration()).contains(REGISTRATION);
            assertThat(reply).isEqualTo(journal.current.get());
        });
        assertThat(journal.current.get().version()).isEqualTo(5);
    }

    @Test
    void normalCompletionAndRecoveryConvergeOnRegistrationHandshake() throws Exception {
        var journal = new Journal(linked(REGISTRATION, new SourceKey("digest"))
                .terminal(DocumentTerminalOutcome.SUCCEEDED, NOW));
        var registrations = new Registrations();
        journal.finalizationBarrier = new CyclicBarrier(2);
        var normal = service(journal, registrations, 1);
        var recovery = service(journal, registrations, 2);

        var replies = concurrent(() -> normal.complete(ID, DocumentTerminalOutcome.SUCCEEDED),
                () -> recovery.recover(1).getFirst());

        assertThat(replies).containsOnly(journal.current.get());
        assertThat(journal.current.get().registrationFinalized()).isTrue();
        assertThat(normal.recover(1)).isEmpty();
        assertThat(normal.complete(ID, DocumentTerminalOutcome.SUCCEEDED)).isEqualTo(journal.current.get());
    }

    @Test
    void conflictingConcurrentTerminalOutcomeIsRejected() {
        var journal = new Journal(linked(REGISTRATION, new SourceKey("digest")));
        journal.concurrentUpdate.set(value -> value.terminal(DocumentTerminalOutcome.REJECTED, NOW.plusSeconds(1)));

        assertThatThrownBy(() -> service(journal, new Registrations(), 2)
                .complete(ID, DocumentTerminalOutcome.SUCCEEDED)).isInstanceOf(IllegalStateException.class);
        assertThat(journal.current.get().terminalOutcome()).contains(DocumentTerminalOutcome.REJECTED);
        assertThat(journal.current.get().registrationFinalized()).isFalse();
    }

    @Test
    void equivalentOutcomeDoesNotPermitChangedSourceAuthority() {
        var journal = new Journal(linked(REGISTRATION, new SourceKey("digest")));
        journal.concurrentUpdate.set(value -> linked(REGISTRATION, new SourceKey("other"))
                .terminal(DocumentTerminalOutcome.SUCCEEDED, NOW.plusSeconds(1)));

        assertThatThrownBy(() -> service(journal, new Registrations(), 2)
                .complete(ID, DocumentTerminalOutcome.SUCCEEDED)).isInstanceOf(IllegalStateException.class);
        assertThat(journal.current.get().registrationFinalized()).isFalse();
    }

    @Test
    void equivalentOutcomeDoesNotPermitChangedRegistrationReference() {
        var journal = new Journal(linked(REGISTRATION, new SourceKey("digest")));
        var changed = new RegisteredObservation(ID, "namespace", new ObservationOrder(2), ObservationOrigin.DOCUMENT);
        journal.concurrentUpdate.set(value -> linked(changed, new SourceKey("digest"))
                .terminal(DocumentTerminalOutcome.SUCCEEDED, NOW.plusSeconds(1)));

        assertThatThrownBy(() -> service(journal, new Registrations(), 2)
                .complete(ID, DocumentTerminalOutcome.SUCCEEDED)).isInstanceOf(IllegalStateException.class);
        assertThat(journal.current.get().registrationFinalized()).isFalse();
    }

    @Test
    void finalizedCompletionStillVerifiesDataframeRegistrationPins() {
        var journal = new Journal(linked(REGISTRATION, new SourceKey("digest"))
                .terminal(DocumentTerminalOutcome.SUCCEEDED, NOW).registrationFinalized(NOW));
        var registrations = new Registrations();
        registrations.actual.set(new RegisteredObservation(ID, "namespace", new ObservationOrder(2), ObservationOrigin.DOCUMENT));

        assertThatThrownBy(() -> service(journal, registrations, 2)
                .complete(ID, DocumentTerminalOutcome.SUCCEEDED)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("registration reference changed");
    }

    @Test
    void rejectedCasWithoutDurableTerminalProgressDoesNotBecomeSuccess() {
        var journal = new Journal(linked(REGISTRATION, new SourceKey("digest")));
        journal.concurrentUpdate.set(UnaryOperator.identity());

        assertThatThrownBy(() -> service(journal, new Registrations(), 2)
                .complete(ID, DocumentTerminalOutcome.SUCCEEDED)).isInstanceOf(IllegalStateException.class);
        assertThat(journal.current.get().phase()).isEqualTo(DocumentAdmissionPhase.LINKED);
    }

    private static DocumentAdmission linked(RegisteredObservation registration, SourceKey key) {
        var evidence = new DocumentCandidateEvidence(Optional.of("file-key"), 1, 1);
        var reservation = new DocumentAdmissionReservation(ID, Path.of("inbox/document.docx"), evidence,
                Path.of("processing/document.docx"), NOW);
        return DocumentAdmission.reserved(reservation).ordered(registration, NOW)
                .claimed(evidence, NOW).linked(key, NOW);
    }

    private static DocumentAdmissionService service(Journal journal, Registrations registrations, int offset) {
        return new DocumentAdmissionService(journal, registrations,
                Clock.fixed(NOW.plusSeconds(offset), ZoneOffset.UTC));
    }

    private static List<DocumentAdmission> concurrent(Callable<DocumentAdmission> first,
                                                     Callable<DocumentAdmission> second) throws Exception {
        var workers = Executors.newFixedThreadPool(2);
        try {
            var one = workers.submit(first);
            var two = workers.submit(second);
            return List.of(one.get(5, TimeUnit.SECONDS), two.get(5, TimeUnit.SECONDS));
        } finally {
            workers.shutdownNow();
            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).as("owned completion workers terminate").isTrue();
        }
    }

    private static final class Journal implements DocumentAdmissionJournal {
        private final AtomicReference<DocumentAdmission> current;
        private final AtomicReference<UnaryOperator<DocumentAdmission>> concurrentUpdate = new AtomicReference<>();
        private CyclicBarrier terminalBarrier;
        private CyclicBarrier finalizationBarrier;

        private Journal(DocumentAdmission initial) { current = new AtomicReference<>(initial); }
        @Override public DocumentAdmission reserve(DocumentAdmissionReservation reservation) { throw new UnsupportedOperationException(); }
        @Override public Optional<DocumentAdmission> find(ObservationId id) { return Optional.of(current.get()); }
        @Override public boolean replace(DocumentAdmission expected, DocumentAdmission updated) {
            await(expected.phase() == DocumentAdmissionPhase.LINKED ? terminalBarrier : finalizationBarrier);
            var competing = concurrentUpdate.getAndSet(null);
            if (competing != null) { current.set(competing.apply(expected)); return false; }
            return current.compareAndSet(expected, updated);
        }
        @Override public List<DocumentAdmission> findRecoverable(int limit) {
            var value = current.get();
            return value.registrationFinalized() ? List.of() : List.of(value);
        }
        @Override public List<DocumentAdmission> findTerminalBefore(Instant cutoff, int limit) { return List.of(); }
        @Override public boolean purgeTerminal(ObservationId id, long version) { return false; }

        private static void await(CyclicBarrier barrier) {
            if (barrier == null) { return; }
            try { barrier.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Completion coordination interrupted", interrupted);
            } catch (java.util.concurrent.BrokenBarrierException | java.util.concurrent.TimeoutException failure) {
                throw new IllegalStateException("Completion coordination failed", failure);
            }
        }
    }

    private static final class Registrations implements ObservationRegistrationStore {
        private final AtomicReference<RegisteredObservation> actual = new AtomicReference<>(REGISTRATION);
        @Override public RegisteredObservation registerNew(ObservationId id, ObservationOrigin origin) { throw new UnsupportedOperationException(); }
        @Override public RegisteredObservation resume(ObservationId id, String namespace) {
            var value = actual.get();
            if (!id.equals(value.observationId()) || !namespace.equals(value.namespaceId())) {
                throw new IllegalStateException("Registration identity changed");
            }
            return value;
        }
        @Override public void markTerminal(ObservationId id, String namespace) { resume(id, namespace); }
        @Override public boolean purgeTerminal(RegisteredObservation registration) { return false; }
    }
}
