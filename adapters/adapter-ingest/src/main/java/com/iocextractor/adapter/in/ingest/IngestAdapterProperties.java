package com.iocextractor.adapter.in.ingest;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Adapter-local binding of {@code ioc.ingestion.*}. Bootstrap keeps the full
 * application property model; this adapter binds only the fields required to
 * discover and move source files.
 */
@ConfigurationProperties(prefix = "ioc.ingestion")
public record IngestAdapterProperties(Dirs dirs,
                                      Patterns patterns,
                                      Detect detect,
                                      Stability stability,
                                      Retry retry,
                                      Ledger ledger,
                                      int concurrency) {

    public record Dirs(String inbox, String processing, String done, String failed) {
    }

    public record Patterns(List<String> include, List<String> exclude) {
    }

    public record Detect(boolean useWatchService, Duration reconcileInterval, int maxMessagesPerPoll) {
    }

    public record Stability(Duration quietPeriod) {
    }

    public record Retry(int maxAttempts, Duration backoff) {
    }

    public record Ledger(String type, String path) {
    }
}
