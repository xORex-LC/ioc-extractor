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
    void count_retention_keeps_newest_entries_globally() {
        Path base = Path.of("var/done");
        var store = new MemoryRetentionStore(List.of(
                entry(base, "newest.html", Duration.ofHours(1)),
                entry(base, "middle.html", Duration.ofHours(2)),
                entry(base, "oldest.html", Duration.ofHours(3))));
        var target = new RetentionTarget("done", base, null, 2, RetentionAction.DELETE, null);
        var service = new RetentionService(store, List.of(target), Clock.fixed(NOW, ZoneOffset.UTC));

        var result = service.run();

        assertThat(result.scanned()).isEqualTo(3);
        assertThat(result.reaped()).isEqualTo(1);
        assertThat(store.deleted)
                .extracting(Path::toString)
                .containsExactly("var/done/oldest.html");
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
