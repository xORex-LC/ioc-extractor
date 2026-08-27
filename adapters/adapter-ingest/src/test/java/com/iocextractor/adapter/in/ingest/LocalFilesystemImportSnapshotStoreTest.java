package com.iocextractor.adapter.in.ingest;

import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportManagedObjectId;
import com.iocextractor.application.dataframeimport.model.ImportSnapshotReference;
import com.iocextractor.common.IocExtractorException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalFilesystemImportSnapshotStoreTest {

    private static final ImportDeliveryId DELIVERY = new ImportDeliveryId("delivery-1");

    @TempDir
    Path tempDir;

    @Test
    void publishesOnceAndAdoptsExistingBytesWithoutCallingWriterAgain() throws Exception {
        LocalFilesystemImportSnapshotStore store =
                new LocalFilesystemImportSnapshotStore(tempDir, 1024);
        AtomicInteger writes = new AtomicInteger();

        var first = store.materialize(DELIVERY, target -> {
            writes.incrementAndGet();
            Files.writeString(target, "payload");
        });
        var adopted = store.materialize(DELIVERY, target -> writes.incrementAndGet());

        assertThat(adopted).isEqualTo(first);
        assertThat(writes).hasValue(1);
        assertThat(Files.readString(store.resolve(first.reference()))).isEqualTo("payload");
    }

    @Test
    void resolvesBothLegacyPrefixesWithoutRewritingEvidence() {
        LocalFilesystemImportSnapshotStore store =
                new LocalFilesystemImportSnapshotStore(tempDir, 1024);
        String token = ImportManagedObjectId.from(DELIVERY).value();

        assertThat(store.resolve(new ImportSnapshotReference("local-snapshot-v1:" + token)))
                .isEqualTo(tempDir.resolve(token).resolve("snapshot.csv"));
        assertThat(store.resolve(new ImportSnapshotReference("smb-snapshot-v1:" + token)))
                .isEqualTo(tempDir.resolve(token).resolve("snapshot.csv"));
    }

    @Test
    void rejectsOversizedBytesAndPurgesPartialPublication() {
        LocalFilesystemImportSnapshotStore store =
                new LocalFilesystemImportSnapshotStore(tempDir, 3);

        assertThatThrownBy(() -> store.materialize(
                DELIVERY, target -> Files.writeString(target, "four")))
                .isInstanceOf(IocExtractorException.class)
                .hasMessageContaining("byte limit");

        store.purge(DELIVERY);
        assertThat(tempDir.resolve(ImportManagedObjectId.from(DELIVERY).value()))
                .doesNotExist();
    }
}
