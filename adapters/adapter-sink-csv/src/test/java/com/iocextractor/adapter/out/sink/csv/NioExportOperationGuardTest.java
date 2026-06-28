package com.iocextractor.adapter.out.sink.csv;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NioExportOperationGuardTest {

    @TempDir
    Path tempDir;

    @Test
    void excludesAnotherInstanceAndReleasesOwnership() {
        var first = new NioExportOperationGuard(tempDir);
        var second = new NioExportOperationGuard(tempDir);

        try (var ignored = first.acquire()) {
            assertThatThrownBy(second::acquire)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("operation is active");
        }

        second.acquire().close();
    }

    @Test
    void excludesConcurrentThreadAndLeaseCloseIsIdempotent() throws Exception {
        var guard = new NioExportOperationGuard(tempDir);
        var lease = guard.acquire();

        try (var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> assertThatThrownBy(guard::acquire)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("operation is active")).get();
        }

        lease.close();
        lease.close();
        guard.acquire().close();
    }
}
