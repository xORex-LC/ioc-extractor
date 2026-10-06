package com.iocextractor.bootstrap;

import com.iocextractor.adapter.in.ingest.DurableDocumentDispatcher;
import com.iocextractor.adapter.out.store.jdbc.JdbcDocumentPreparationWorkspaceFactory;
import com.iocextractor.adapter.out.store.jdbc.JdbcSnapshotSliceReader;
import com.iocextractor.adapter.out.store.jdbc.JdbcWriterAdmission;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.health.contributor.Status;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DataProcessingCapacityHealthIndicatorTest {
    @TempDir Path root;

    @Test
    void reportsBoundedCountersAndWalWithoutHidingBlockedOwnedDocuments() throws Exception {
        var documents = mock(DurableDocumentDispatcher.class);
        var readers = mock(JdbcSnapshotSliceReader.class);
        var workspaces = mock(JdbcDocumentPreparationWorkspaceFactory.class);
        var writers = new JdbcWriterAdmission();
        String url = "jdbc:sqlite:" + root.resolve("canonical.db");
        Files.write(DataProcessingCapacityHealthIndicator.walPath(url), new byte[137]);
        when(documents.snapshot()).thenReturn(new DurableDocumentDispatcher.Snapshot(
                true, 1, 100, 4000, 0, 0, 0, 1, 3, 2, 200, 50, "blocked"));
        when(readers.activeReaders()).thenReturn(1);
        when(workspaces.pressure()).thenReturn(new JdbcDocumentPreparationWorkspaceFactory.Pressure(
                1, 64, 100, 1000, 64));
        writers.execute(JdbcWriterAdmission.OperationClass.CONTROL, () -> null);
        var indicator = new DataProcessingCapacityHealthIndicator(documents, writers, readers, workspaces, url);
        var health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("walBytes", 137L).containsEntry("activeSnapshotReaders", 1)
                .containsEntry("writerOperations", writers.snapshot());
    }

    @Test
    void resolvesUriFileWalAndSkipsMemoryDatabases() {
        assertThat(DataProcessingCapacityHealthIndicator.walPath("jdbc:sqlite:" + root.resolve("db.sqlite")))
                .isEqualTo(DataProcessingCapacityHealthIndicator.walPath("jdbc:sqlite:" + root.resolve("db.sqlite").toUri() + "?mode=ro"));
        assertThat(DataProcessingCapacityHealthIndicator.walPath("jdbc:sqlite::memory:")).isNull();
        assertThat(DataProcessingCapacityHealthIndicator.walPath("jdbc:sqlite:file:shared?mode=memory&cache=shared")).isNull();
    }
}
