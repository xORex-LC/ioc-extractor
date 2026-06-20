package com.iocextractor.bootstrap;

import com.iocextractor.domain.model.IndicatorType;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * Type-safe binding of the {@code ioc.*} configuration tree. This is the only
 * place the external configuration shape is known; the domain stays config-free.
 */
@ConfigurationProperties(prefix = "ioc")
public record IocProperties(
        String engine,
        Source source,
        Refang refang,
        Map<IndicatorType, String> patterns,
        Classify classify,
        Sink sink,
        Lookup lookup) {

    public record Source(String type, String charset, List<String> sectionMarkers) {
    }

    public record Refang(List<Rule> rules) {
        public record Rule(String from, String to) {
        }
    }

    public record Classify(Codes bareHost, Codes fullUrl) {
        public record Codes(String urlMatch, String hostMatch) {
        }
    }

    public record Sink(Csv csv, List<Artifact> artifacts) {

        public record Csv(String delimiter, String quote, String nullLiteral) {
        }

        public record Artifact(
                String name,
                boolean enabled,
                String mapper,
                String path,
                List<IndicatorType> accepts,
                String valueCase,
                Id id,
                String header,
                String sourceStripPrefix) {

            public record Id(String strategy, String start) {
            }
        }
    }

    public record Lookup(String type, String path, boolean deduplicate) {
    }
}
