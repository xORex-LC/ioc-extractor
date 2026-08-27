package com.iocextractor.adapter.out.transport.smb;

import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportManagedObjectId;
import com.iocextractor.application.dataframeimport.model.ImportSha256;
import com.iocextractor.application.dataframeimport.model.ImportSnapshot;
import com.iocextractor.application.dataframeimport.model.ImportSnapshotReference;
import com.iocextractor.application.port.out.dataframeimport.ImportSnapshotStore;
import com.iocextractor.application.port.out.dataframeimport.ImportSnapshotWriter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Minimal test double; production durability belongs to adapter-ingest. */
final class TestImportSnapshotStore implements ImportSnapshotStore {

    private static final String PREFIX = "local-snapshot-v1:";
    private final Path root;

    TestImportSnapshotStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public ImportSnapshot materialize(ImportDeliveryId deliveryId, ImportSnapshotWriter writer) {
        try {
            String token = ImportManagedObjectId.from(deliveryId).value();
            Path directory = root.resolve(token);
            Path published = directory.resolve("snapshot.csv");
            if (!Files.exists(published)) {
                Files.createDirectories(directory);
                Path part = directory.resolve("snapshot.part");
                Files.deleteIfExists(part);
                writer.write(part);
                Files.move(part, published);
            }
            byte[] bytes = Files.readAllBytes(published);
            String digest = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
            return new ImportSnapshot(
                    new ImportSnapshotReference(PREFIX + token), new ImportSha256(digest), bytes.length);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Override
    public Path resolve(ImportSnapshotReference reference) {
        int separator = reference.value().indexOf(':');
        if (separator < 0) {
            throw new IllegalArgumentException("Malformed test snapshot reference");
        }
        return root.resolve(reference.value().substring(separator + 1)).resolve("snapshot.csv");
    }

    @Override
    public void purge(ImportDeliveryId deliveryId) {
        try {
            Path directory = root.resolve(ImportManagedObjectId.from(deliveryId).value());
            Files.deleteIfExists(directory.resolve("snapshot.part"));
            Files.deleteIfExists(directory.resolve("snapshot.csv"));
            Files.deleteIfExists(directory);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
