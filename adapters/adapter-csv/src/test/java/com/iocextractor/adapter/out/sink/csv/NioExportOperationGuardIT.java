package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.application.tck.junit.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
@Timeout(10)
class NioExportOperationGuardIT {

    @TempDir
    Path tempDir;

    @Test
    void excludesAnotherInstanceAndReleasesOwnership() {
        var first = new NioExportOperationGuard(tempDir);
        var second = new NioExportOperationGuard(tempDir);

        try (var ignored = first.acquire("one")) {
            assertThatThrownBy(() -> second.acquire("one"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("operation is active");
        }

        second.acquire("one").close();
    }

    @Test
    void allowsDifferentProfilesAndKeepsTheBusyProfileExcluded() {
        var guard = new NioExportOperationGuard(tempDir);
        try (var one = guard.acquire("one"); var two = guard.acquire("two")) {
            assertThatThrownBy(() -> guard.acquire("one")).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> new NioExportOperationGuard(tempDir).acquire("one"))
                    .isInstanceOf(IllegalStateException.class);
        }
        guard.acquire("one").close();
    }

    @Test
    void excludesConcurrentThreadAndLeaseCloseIsIdempotent() throws Exception {
        var guard = new NioExportOperationGuard(tempDir);
        var lease = guard.acquire("one");

        try (var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> assertThatThrownBy(() -> guard.acquire("one"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("operation is active")).get();
        }

        lease.close();
        lease.close();
        guard.acquire("one").close();
    }
}
