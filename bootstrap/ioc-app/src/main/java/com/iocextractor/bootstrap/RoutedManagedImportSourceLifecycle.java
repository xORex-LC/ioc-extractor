package com.iocextractor.bootstrap;

import com.iocextractor.adapter.in.csv.ImportSnapshotPathResolver;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportSnapshotReference;
import com.iocextractor.application.dataframeimport.model.ImportSourceCandidate;
import com.iocextractor.application.dataframeimport.model.ImportSourceId;
import com.iocextractor.application.port.out.dataframeimport.ClaimImportSourceCommand;
import com.iocextractor.application.port.out.dataframeimport.ClaimImportSourceResult;
import com.iocextractor.application.port.out.dataframeimport.DispositionImportSourceCommand;
import com.iocextractor.application.port.out.dataframeimport.ManagedImportSourceLifecycle;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bootstrap-only router across transport-specific source ownership adapters. */
final class RoutedManagedImportSourceLifecycle
        implements ManagedImportSourceLifecycle, ImportSnapshotPathResolver {

    private final Map<ImportSourceId, ManagedImportSourceLifecycle> routes;
    private final List<ImportSnapshotPathResolver> snapshots;

    RoutedManagedImportSourceLifecycle(
            Map<ImportSourceId, ManagedImportSourceLifecycle> routes,
            List<ImportSnapshotPathResolver> snapshots) {
        this.routes = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(routes, "routes")));
        this.snapshots = List.copyOf(Objects.requireNonNull(snapshots, "snapshots"));
        if (routes.isEmpty() || snapshots.isEmpty()) {
            throw new IllegalArgumentException("Managed import source routing must not be empty");
        }
    }

    @Override
    public List<ImportSourceCandidate> detect(ImportSourceId sourceId, Instant observedAt) {
        return route(sourceId).detect(sourceId, observedAt);
    }

    @Override
    public ClaimImportSourceResult claim(ClaimImportSourceCommand command) {
        return route(command.sourceId()).claim(command);
    }

    @Override
    public void disposition(DispositionImportSourceCommand command) {
        route(command.sourceId()).disposition(command);
    }

    @Override
    public void purgeSnapshot(ImportDeliveryId deliveryId, ImportSourceId sourceId) {
        route(sourceId).purgeSnapshot(deliveryId, sourceId);
    }

    @Override
    public Path resolve(ImportSnapshotReference reference) {
        IllegalArgumentException unsupported = null;
        for (ImportSnapshotPathResolver resolver : snapshots) {
            try {
                return resolver.resolve(reference);
            } catch (IllegalArgumentException candidate) {
                unsupported = candidate;
            }
        }
        throw new IllegalArgumentException(
                "Unsupported managed import snapshot reference", unsupported);
    }

    private ManagedImportSourceLifecycle route(ImportSourceId sourceId) {
        Objects.requireNonNull(sourceId, "sourceId");
        ManagedImportSourceLifecycle lifecycle = routes.get(sourceId);
        if (lifecycle == null) {
            throw new IllegalArgumentException("Unknown managed import source");
        }
        return lifecycle;
    }
}
