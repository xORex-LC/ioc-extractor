package com.iocextractor.application.port.out.observation;

import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.observation.ObservationAdmissionReference;
import com.iocextractor.application.observation.RegisteredObservation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Service-side recovery reference for import delivery admission. */
public interface ObservationAdmissionReferenceStore {

    /**
     * Returns whether the service ledger durably proves that this occurrence was
     * reserved by the ordered-admission protocol and may allocate a new rank.
     */
    boolean isRegistrationReserved(ObservationId observationId);

    ObservationAdmissionReference link(RegisteredObservation registration);

    Optional<ObservationAdmissionReference> find(ObservationId observationId);

    boolean replace(ObservationAdmissionReference expected, ObservationAdmissionReference updated);

    List<ObservationAdmissionReference> findUnfinalized(int limit);

    List<ObservationAdmissionReference> findFinalizedBefore(Instant cutoff, int limit);

    boolean purgeFinalized(ObservationId observationId, long expectedVersion);
}
