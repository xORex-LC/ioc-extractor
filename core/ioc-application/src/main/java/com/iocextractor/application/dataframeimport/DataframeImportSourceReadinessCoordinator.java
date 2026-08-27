package com.iocextractor.application.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportSourceId;
import com.iocextractor.application.dataframeimport.model.ImportSourceReadiness;
import com.iocextractor.application.dataframeimport.model.ImportSourceReadinessStatus;
import com.iocextractor.application.port.out.dataframeimport.ImportSourceCapability;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Source-scoped capability gate shared by polling and change-notify detection. */
public final class DataframeImportSourceReadinessCoordinator {

    private final ImportSourceCapability capability;
    private final Map<ImportSourceId, ImportSourceReadiness> latest = new ConcurrentHashMap<>();

    /** Creates a coordinator over transport-routed capability adapters. */
    public DataframeImportSourceReadinessCoordinator(ImportSourceCapability capability) {
        this.capability = Objects.requireNonNull(capability, "capability");
    }

    /** Reprobes one source and returns whether intake may proceed. */
    public boolean ready(ImportSourceId sourceId) {
        ImportSourceReadiness current = latest.get(Objects.requireNonNull(sourceId, "sourceId"));
        if (current != null && current.status() == ImportSourceReadinessStatus.READY) {
            return true;
        }
        ImportSourceReadiness result = Objects.requireNonNull(
                capability.probe(sourceId), "source readiness");
        if (!result.sourceId().equals(sourceId)) {
            throw new DataframeImportConsistencyException(
                    "Import source capability returned evidence for another source");
        }
        latest.put(sourceId, result);
        return result.status() == ImportSourceReadinessStatus.READY;
    }

    /** Returns the latest value-free readiness evidence for one source. */
    public Optional<ImportSourceReadiness> latest(ImportSourceId sourceId) {
        return Optional.ofNullable(latest.get(Objects.requireNonNull(sourceId, "sourceId")));
    }

    /** Returns a stable snapshot of the latest value-free source states. */
    public List<ImportSourceReadiness> snapshot() {
        return latest.values().stream()
                .sorted(Comparator.comparing(value -> value.sourceId().value()))
                .toList();
    }
}
