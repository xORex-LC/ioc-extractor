package com.iocextractor.application.port.out.artifact.lifecycle;

import com.iocextractor.application.artifact.lifecycle.ConfirmationReceiptSnapshot;
import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.artifact.lifecycle.ObservationId;

import java.util.Optional;

/** Pins one complete receipt while an ordered document may require its exact prepared rows. */
public interface DocumentReceiptPlans {

    /** Selects an unexpired receipt for a new pin, or returns this observation's existing pin. */
    Optional<ConfirmationReceiptSnapshot> pin(ObservationId id, String sourceKey, String policy, EffectiveTime asOf);

    /** Reads only the receipt pinned by this observation, independently of its reuse deadline. */
    Optional<ConfirmationReceiptSnapshot> pinned(ObservationId id, String sourceKey, String policy);

    /** Releases a pin after publication has finished or the source is durably rejected. */
    void release(ObservationId id);
}
