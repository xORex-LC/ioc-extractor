package com.iocextractor.application.port.out.ingest;

import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.ingest.admission.DocumentAdmission;
import com.iocextractor.application.ingest.admission.DocumentAdmissionReservation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Durable CAS journal shared by JDBC and file-ledger daemon modes. */
public interface DocumentAdmissionJournal {

    DocumentAdmission reserve(DocumentAdmissionReservation reservation);

    Optional<DocumentAdmission> find(ObservationId observationId);

    boolean replace(DocumentAdmission expected, DocumentAdmission updated);

    List<DocumentAdmission> findRecoverable(int limit);

    List<DocumentAdmission> findTerminalBefore(Instant cutoff, int limit);

    boolean purgeTerminal(ObservationId observationId, long expectedVersion);
}
