package com.iocextractor.bootstrap;

import com.iocextractor.adapter.in.ingest.DurableDocumentDispatcher;
import com.iocextractor.adapter.out.store.jdbc.JdbcDocumentPreparationWorkspaceFactory;
import com.iocextractor.adapter.out.store.jdbc.JdbcSnapshotSliceReader;
import com.iocextractor.adapter.out.store.jdbc.JdbcWriterAdmission;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

    @ParameterizedTest
    @ValueSource(strings = {"jdbc:sqlite::memory:", "jdbc:sqlite:file:shared?mode=memory", "jdbc:other:db", "jdbc:sqlite:absent.db"})
    void healthyIdleExecutionDoesNotRequireAnExistingWal(String url) {
        var documents = mock(DurableDocumentDispatcher.class);
        var readers = mock(JdbcSnapshotSliceReader.class);
        var workspaces = mock(JdbcDocumentPreparationWorkspaceFactory.class);
        when(documents.snapshot()).thenReturn(new DurableDocumentDispatcher.Snapshot(
                true, 0, 0, 4000, 0, 0, 0, 0, 0, 0, 0, 0, ""));
        when(workspaces.pressure()).thenReturn(new JdbcDocumentPreparationWorkspaceFactory.Pressure(
                0, 64, 0, 1000, 64));
        var health = new DataProcessingCapacityHealthIndicator(documents, new JdbcWriterAdmission(),
                readers, workspaces, url).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("walBytes", 0L)
                .containsEntry("activeSnapshotReaders", 0).containsEntry("queuedWriters", 0);
    }

    @Test
    void failedJournalInspectionReportsDegradationInsteadOfHealthyEmptyCounters() {
        var documents = mock(DurableDocumentDispatcher.class);
        when(documents.snapshot()).thenThrow(new IllegalStateException("journal unavailable"));
        var health = new DataProcessingCapacityHealthIndicator(documents, new JdbcWriterAdmission(),
                mock(JdbcSnapshotSliceReader.class), mock(JdbcDocumentPreparationWorkspaceFactory.class),
                "jdbc:sqlite::memory:").health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("error", "java.lang.IllegalStateException: journal unavailable");
    }
}
