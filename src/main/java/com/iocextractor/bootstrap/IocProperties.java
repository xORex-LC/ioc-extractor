package com.iocextractor.bootstrap;

import com.iocextractor.domain.model.IndicatorType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

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
        @NotNull @Valid Source source,
        @NotNull @Valid Refang refang,
        @NotEmpty Map<IndicatorType, String> patterns,
        @NotNull @Valid Classify classify,
        @NotNull @Valid Sink sink,
        @NotNull @Valid Lookup lookup) {

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

        public record Csv(@NotBlank String delimiter, @NotBlank String quote, @NotBlank String nullLiteral) {
        }

        public record Artifact(
                @NotBlank String name,
                boolean enabled,
                @NotBlank String path,
                @NotEmpty List<IndicatorType> accepts,
                Id id,
                @NotEmpty @Valid List<Column> columns) {

            public record Id(String strategy, String start) {
            }

            public record Column(
                    @NotBlank String name,
                    @NotBlank String from,
                    String value,
                    IndicatorType whenType,
                    List<String> transform) {
            }
        }
    }

    public record Lookup(String type, @NotBlank String path, boolean deduplicate) {
    }
}
