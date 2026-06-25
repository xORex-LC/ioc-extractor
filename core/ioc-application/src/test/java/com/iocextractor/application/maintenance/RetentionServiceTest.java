package com.iocextractor.application.maintenance;

import com.iocextractor.application.port.out.maintenance.RetentionStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RetentionServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-25T00:00:00Z");

    @Test
    void partition_count_retention_keeps_newest_entries_per_artifact_group() {
        Path base = Path.of("dataframe/partitions");
        var store = new MemoryRetentionStore(List.of(
                entry(base, "masks/2026-06-25/new.csv", Duration.ofHours(1)),
                entry(base, "masks/2026-06-24/old.csv", Duration.ofHours(2)),
                entry(base, "hashes/2026-06-25/new.csv", Duration.ofHours(1)),
                entry(base, "hashes/2026-06-24/old.csv", Duration.ofHours(2))));
        var target = new RetentionTarget("partitions", base, null, 1, RetentionAction.DELETE, null);
        var service = new RetentionService(store, List.of(target), Clock.fixed(NOW, ZoneOffset.UTC));

        var result = service.run();

        assertThat(result.scanned()).isEqualTo(4);
        assertThat(result.reaped()).isEqualTo(2);
        assertThat(store.deleted)
                .extracting(Path::toString)
                .containsExactlyInAnyOrder(
                        "dataframe/partitions/masks/2026-06-24/old.csv",
                        "dataframe/partitions/hashes/2026-06-24/old.csv");
    }

    private RetentionEntry entry(Path base, String relative, Duration age) {
        return new RetentionEntry(base.resolve(relative), NOW.minus(age), base);
    }

    private static final class MemoryRetentionStore implements RetentionStore {
        private final List<RetentionEntry> entries;
        private final List<Path> deleted = new ArrayList<>();

        private MemoryRetentionStore(List<RetentionEntry> entries) {
            this.entries = List.copyOf(entries);
        }

        @Override
        public List<RetentionEntry> list(Path dir) {
            return entries;
        }

        @Override
        public void delete(RetentionEntry entry) {
            deleted.add(entry.path());
        }

        @Override
        public void archive(RetentionEntry entry, Path archiveDir) {
            throw new UnsupportedOperationException();
        }
    }
}
