package com.iocextractor.adapter.in.ingest;

import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportSnapshotReference;

import java.nio.file.Path;
import java.util.Objects;

/** Resolves the shared private local snapshot namespace, including SMB replay copies. */
public final class LocalImportSnapshotPathResolver {

    static final String REFERENCE_PREFIX = "local-snapshot-v1:";

    private final Path snapshotRoot;

    public LocalImportSnapshotPathResolver(Path snapshotRoot) {
        this.snapshotRoot = Objects.requireNonNull(snapshotRoot, "snapshotRoot")
                .toAbsolutePath().normalize();
    }

    /** Creates an opaque reference for one globally unique delivery. */
    public static ImportSnapshotReference referenceFor(ImportDeliveryId deliveryId) {
        return new ImportSnapshotReference(
                REFERENCE_PREFIX + LocalManagedImportSourceLifecycle.deliveryToken(deliveryId));
    }

    /** Resolves one reference without accepting raw paths. */
    public Path resolve(ImportSnapshotReference reference) {
        Objects.requireNonNull(reference, "reference");
        if (!reference.value().startsWith(REFERENCE_PREFIX)) {
            throw new IllegalArgumentException("Unsupported local import snapshot reference");
        }
        String token = reference.value().substring(REFERENCE_PREFIX.length());
        if (!token.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Malformed local import snapshot reference");
        }
        Path resolved = snapshotRoot.resolve(token).resolve("snapshot.csv").normalize();
        if (!resolved.startsWith(snapshotRoot)) {
            throw new IllegalArgumentException("Local import snapshot escapes its private root");
        }
        return resolved;
    }
}
