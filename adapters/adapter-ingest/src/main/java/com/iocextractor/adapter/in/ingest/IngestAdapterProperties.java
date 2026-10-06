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
            public static Execution defaults() { return new Execution(2, 4, 64, 4294967296L, 536870912L); }
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
