package com.iocextractor.bootstrap;

import com.iocextractor.domain.model.IndicatorType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Type-safe binding of the {@code ioc.*} configuration tree. This is the only
 * place the external configuration shape is known; the domain stays config-free.
 *
 * <p>{@link Validated} makes an incomplete configuration fail fast at startup
 * with a clear message, rather than surfacing as an obscure NPE later.
 */
@Validated
@ConfigurationProperties(prefix = "ioc")
public record IocProperties(
        String engine,
        @NotNull @Valid Runtime runtime,
        @NotNull @Valid Storage storage,
        @NotNull @Valid Source source,
        @NotNull @Valid Refang refang,
        @NotEmpty Map<IndicatorType, String> patterns,
        @NotNull @Valid Classify classify,
        @NotNull @Valid Sink sink,
        @NotNull @Valid Lookup lookup,
        @NotNull @Valid Ingestion ingestion,
        @NotNull @Valid Aggregation aggregation,
        @Valid Maintenance maintenance,
        @NotNull @Valid Observability observability) {

    public record Runtime(@NotBlank String mode) {
    }

    /**
     * Storage topology and backend-specific tuning. Runtime code selects a role
     * ({@code service}, later {@code dataframe}) through ports; SQL/JDBC details
     * remain in storage adapters.
     */
    public record Storage(@NotNull @Valid Service service, @NotNull @Valid Dataframe dataframe) {

        public record Service(
                @NotBlank String type,
                @NotBlank String url,
                @NotNull @Valid Sqlite sqlite,
                @NotNull @Valid Pool pool) {
        }

        public record Dataframe(
                @NotBlank String type,
                @NotBlank String url,
                @NotNull @Valid Sqlite sqlite,
                @NotNull @Valid Pool pool) {
        }

        public record Sqlite(@NotBlank String tuning) {
        }

        public record Pool(@Positive int writeMax, @Positive int readMax) {
        }
    }

    public record Source(@NotBlank String type, String charset, @NotNull List<String> sectionMarkers) {
    }

    public record Refang(@NotNull @Valid List<Rule> rules) {
        public record Rule(@NotNull String from, @NotNull String to) {
        }
    }

    public record Classify(@NotEmpty @Valid List<Rule> rules) {
        public record Rule(@NotNull List<String> when, @NotBlank String urlMatch, String hostMatch) {
        }
    }

    public record Sink(@NotNull @Valid Csv csv, @NotEmpty @Valid List<Artifact> artifacts) {

        public record Csv(@NotBlank String delimiter, @NotBlank String quote, @NotBlank String nullLiteral,
                          String charset) {
        }

        public record Artifact(
                @NotBlank String name,
                boolean enabled,
                @NotBlank String path,
                @NotEmpty List<IndicatorType> accepts,
                List<String> include,
                List<String> exclude,
                Id id,
                @NotEmpty @Valid List<Column> columns) {

            public record Id(String strategy, String start) {
            }

            public record Column(
                    @NotBlank String name,
                    @NotBlank String from,
                    String value,
                    String type,
                    IndicatorType whenType,
                    List<String> transform) {
            }
        }
    }

    public record Lookup(String type, @NotBlank String path, boolean deduplicate, List<Artifact> artifacts) {
        public record Artifact(@NotBlank String name, @NotBlank String path) {
        }
    }

    public record Aggregation(
            boolean enabled,
            String trigger,
            @NotNull Duration interval,
            @NotNull Duration initialDelay,
            @NotEmpty @Valid List<Artifact> artifacts) {

        public record Artifact(
                @NotBlank String name,
                @NotEmpty List<String> keyColumns,
                String keyMode,
                @Positive Integer epoch,
                @NotBlank String conflictPolicy) {
        }
    }

    /**
     * Daemon housekeeping. A single {@link Retention} sweep reaps aged/over-count
     * entries from growing directories ({@code partitions}, {@code done},
     * {@code failed}); each {@link Retention.Target} is configured declaratively.
     */
    public record Maintenance(@Valid Retention retention) {

        public record Retention(
                boolean enabled,
                Duration interval,
                Duration initialDelay,
                @Valid List<Target> targets) {

            public record Target(
                    @NotBlank String name,
                    @NotBlank String dir,
                    Duration maxAge,
                    int maxCount,
                    String action,
                    String archiveDir) {
            }
        }
    }

    public record Ingestion(
            @NotNull @Valid Dirs dirs,
            @NotNull @Valid Patterns patterns,
            @NotNull @Valid Detect detect,
            @NotNull @Valid Stability stability,
            @NotNull @Valid Retry retry,
            @NotNull @Valid Ledger ledger,
            int concurrency) {

        public record Dirs(
                @NotBlank String inbox,
                @NotBlank String processing,
                @NotBlank String done,
                @NotBlank String failed) {
        }

        public record Patterns(@NotEmpty List<String> include, @NotNull List<String> exclude) {
        }

        public record Detect(boolean useWatchService, @NotNull Duration reconcileInterval, int maxMessagesPerPoll) {
        }

        public record Stability(@NotNull Duration quietPeriod) {
        }

        public record Retry(int maxAttempts, @NotNull Duration backoff) {
        }

        public record Ledger(@NotBlank String type, @NotBlank String path) {
        }
    }

    public record Observability(@NotBlank String mode, boolean perItemTraceEnabled) {
    }
}
