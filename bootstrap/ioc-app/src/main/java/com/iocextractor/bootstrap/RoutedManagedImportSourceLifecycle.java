package com.iocextractor.bootstrap;

import com.iocextractor.adapter.in.csv.ImportSnapshotPathResolver;
import com.iocextractor.application.dataframeimport.model.ImportSnapshotReference;
import com.iocextractor.application.dataframeimport.model.ImportSourceCandidate;
import com.iocextractor.application.dataframeimport.model.ImportSourceId;
import com.iocextractor.application.dataframeimport.model.ImportSourceReadiness;
import com.iocextractor.application.port.out.dataframeimport.ClaimImportSourceCommand;
import com.iocextractor.application.port.out.dataframeimport.ClaimImportSourceResult;
import com.iocextractor.application.port.out.dataframeimport.DispositionImportSourceCommand;
import com.iocextractor.application.port.out.dataframeimport.ManagedImportSourceLifecycle;
import com.iocextractor.application.port.out.dataframeimport.ImportTerminalSourceRetention;
import com.iocextractor.application.port.out.dataframeimport.PurgeImportTerminalSourceCommand;
import com.iocextractor.application.port.out.dataframeimport.ImportSourceCapability;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bootstrap-only router across transport-specific source ownership adapters. */
final class RoutedManagedImportSourceLifecycle
        implements ManagedImportSourceLifecycle, ImportSnapshotPathResolver,
        ImportTerminalSourceRetention, ImportSourceCapability {

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
    public ImportSourceReadiness probe(ImportSourceId sourceId) {
        ManagedImportSourceLifecycle lifecycle = route(sourceId);
        if (!(lifecycle instanceof ImportSourceCapability capability)) {
            throw new IllegalStateException("Managed import source has no capability adapter");
        }
        return capability.probe(sourceId);
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
    public void purge(PurgeImportTerminalSourceCommand command) {
        ManagedImportSourceLifecycle lifecycle = route(command.sourceId());
        if (!(lifecycle instanceof ImportTerminalSourceRetention retention)) {
            throw new IllegalStateException("Managed import source has no terminal retention adapter");
        }
        retention.purge(command);
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
