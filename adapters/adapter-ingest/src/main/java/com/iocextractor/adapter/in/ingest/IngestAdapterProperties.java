package com.iocextractor.adapter.in.ingest;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
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
                                      int concurrency, Execution execution) {
        @org.springframework.boot.context.properties.bind.ConstructorBinding
        public IngestAdapterProperties { execution = execution == null ? Execution.defaults() : execution; }
        public IngestAdapterProperties(Dirs dirs, Patterns patterns, Detect detect, Stability stability,
                Retry retry, Ledger ledger, int concurrency) {
            this(dirs, patterns, detect, stability, retry, ledger, concurrency, Execution.defaults());
        }
        public record Execution(int preparationWorkers, int window, int maxPendingDocuments,
                                long maxPendingSourceBytes, long maxSourceBytes) {
            private static final int DEFAULT_PREPARATION_WORKERS = 2;
            private static final int DEFAULT_WINDOW = 4;
            private static final int DEFAULT_PENDING_DOCUMENTS = 64;
            private static final long DEFAULT_PENDING_SOURCE_BYTES = 4L * 1024 * 1024 * 1024;
            private static final long DEFAULT_SOURCE_BYTES = 512L * 1024 * 1024;

            public static Execution defaults() {
                return new Execution(DEFAULT_PREPARATION_WORKERS, DEFAULT_WINDOW, DEFAULT_PENDING_DOCUMENTS,
                        DEFAULT_PENDING_SOURCE_BYTES, DEFAULT_SOURCE_BYTES);
            }
        }


    public record Dirs(String inbox, String processing, String done, String failed) {
    }

    public record Patterns(List<String> include, List<String> exclude) {

        public Patterns {
            include = include == null ? null : Collections.unmodifiableList(new ArrayList<>(include));
            exclude = exclude == null ? null : Collections.unmodifiableList(new ArrayList<>(exclude));
        }
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
