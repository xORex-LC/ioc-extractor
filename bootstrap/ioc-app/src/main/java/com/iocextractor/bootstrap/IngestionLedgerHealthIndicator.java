package com.iocextractor.bootstrap;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Checks that the file-backed ingestion ledger location is accessible.
 */
public final class IngestionLedgerHealthIndicator implements HealthIndicator {

    private final IocProperties props;

    public IngestionLedgerHealthIndicator(IocProperties props) {
        this.props = props;
    }

    @Override
    public Health health() {
        Path path = Path.of(props.ingestion().ledger().path());
        try {
            Files.createDirectories(path);
        } catch (Exception e) {
            return Health.down().withDetail("ledgerDir", path).withDetail("error", e.getMessage()).build();
        }
        if (Files.isDirectory(path) && Files.isWritable(path)) {
            return Health.up().withDetail("ledgerDir", path).build();
        }
        return Health.down().withDetail("ledgerDir", path).build();
    }
}
