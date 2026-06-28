package com.iocextractor.application.export;

import com.iocextractor.application.port.out.export.SliceRetentionStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SliceRetentionServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-28T00:00:00Z");

    @Test
    void countPoolsAreIndependentAndPinnedSlicesMakeLimitBestEffort() {
        FakeStore store = new FakeStore(Map.of(
                "one", List.of(slice("one-a", "one", 30), slice("one-b", "one", 20),
                        slice("one-c", "one", 10)),
                "two", List.of(slice("two-a", "two", 30), slice("two-b", "two", 10))));
        var service = new SliceRetentionService(
                store, slice -> !slice.sliceId().equals("one-a"), List.of("one", "two"),
                null, 1, Clock.fixed(NOW, ZoneOffset.UTC));

        var result = service.run();

        assertThat(result.scanned()).isEqualTo(5);
        assertThat(result.deleted()).isEqualTo(2);
        assertThat(result.blocked()).isEqualTo(1);
        assertThat(result.deletedByProfile()).containsEntry("one", 1).containsEntry("two", 1);
        assertThat(store.deleted).extracting(SliceDescriptor::sliceId)
                .containsExactlyInAnyOrder("one-b", "two-a");
    }

    @Test
    void standaloneGuardAllowsWholeAgeSelectedSlice() {
        FakeStore store = new FakeStore(Map.of(
                "one", List.of(slice("old", "one", 60), slice("new", "one", 5))));
        var service = new SliceRetentionService(
                store, StandaloneSliceRetentionGuard.INSTANCE, List.of("one"),
                Duration.ofSeconds(30), 0, Clock.fixed(NOW, ZoneOffset.UTC));

        var result = service.run();

        assertThat(result.deleted()).isEqualTo(1);
        assertThat(store.deleted).extracting(SliceDescriptor::sliceId).containsExactly("old");
    }

    private SliceDescriptor slice(String id, String profile, long ageSeconds) {
        return new SliceDescriptor(id, profile, id, NOW.minusSeconds(ageSeconds));
    }

    private static final class FakeStore implements SliceRetentionStore {
        private final Map<String, List<SliceDescriptor>> slices = new LinkedHashMap<>();
        private final List<SliceDescriptor> deleted = new ArrayList<>();

        private FakeStore(Map<String, List<SliceDescriptor>> slices) {
            this.slices.putAll(slices);
        }

        @Override
        public List<SliceDescriptor> listCompleted(String profile) {
            return slices.getOrDefault(profile, List.of());
        }

        @Override
        public void delete(SliceDescriptor slice) {
            deleted.add(slice);
        }
    }
}
