package com.iocextractor.application.ingest.admission;

import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.port.out.ingest.DocumentAdmissionJournal;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Operator access to the journal only. The daemon retains sole authority to act on source files. */
public final class DocumentAdmissionOperations {
    private final Supplier<DocumentAdmissionJournal> journal;
    private final Clock clock;

    public DocumentAdmissionOperations(Supplier<DocumentAdmissionJournal> journal, Clock clock) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Returns the named occurrence, or the oldest recoverable occurrence when no ID was provided. */
    public Optional<DocumentAdmission> status(Optional<ObservationId> id) {
        var store = journal.get();
        return id.isPresent() ? store.find(id.orElseThrow()) : store.findRecoverable(1).stream().findFirst();
    }

    /** Releases the exact held revision without changing its rank, intent or ownership evidence. */
    public DocumentAdmission resume(ObservationId id, long version) {
        var store = journal.get();
        var current = store.find(id).orElseThrow(() -> new IllegalStateException("Missing document admission"));
        boolean closedDisposition = current.registrationFinalized()
                || current.terminalOutcome().orElse(null) == DocumentTerminalOutcome.REJECTED;
        if (current.version() != version || closedDisposition || !current.execution().retryAfter().equals(DocumentExecutionState.HELD)) {
            throw new IllegalStateException("Document admission is not held at the requested version");
        }
        var resumed = current.execution(DocumentExecutionState.NEW, clock.instant());
        if (!store.replace(current, resumed)) {
            throw new IllegalStateException("Document admission changed concurrently");
        }
        return resumed;
    }
}
