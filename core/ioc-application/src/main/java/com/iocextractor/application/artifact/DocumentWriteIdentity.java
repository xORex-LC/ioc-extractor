package com.iocextractor.application.artifact;

import com.iocextractor.application.observation.ObservationOrigin;
import com.iocextractor.application.observation.RegisteredObservation;

import java.util.Objects;

/**
 * Binds all canonical commits of an ordered document to its admitted identity and prepared plan.
 *
 * @param registration original dataframe namespace, observation and admission rank
 * @param sourceKey content key of the owned immutable snapshot
 * @param planId durable identity of the exact admitted plan
 * @param lifecycleActive whether canonical writes use the fixed-lifecycle writer
 */
public record DocumentWriteIdentity(RegisteredObservation registration, String sourceKey,
                                    String planId, boolean lifecycleActive) {

    public DocumentWriteIdentity {
        Objects.requireNonNull(registration, "registration");
        if (registration.origin() != ObservationOrigin.DOCUMENT
                || sourceKey == null || sourceKey.isBlank() || planId == null || planId.isBlank()) {
            throw new IllegalArgumentException("Document write requires an admitted document and a named plan");
        }
    }
}
