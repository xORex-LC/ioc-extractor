package com.iocextractor.bootstrap;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Checks daemon artifact storage directories.
 */
public final class ArtifactStorageHealthIndicator implements HealthIndicator {

    private final IocProperties props;

    public ArtifactStorageHealthIndicator(IocProperties props) {
        this.props = props;
    }

    @Override
    public Health health() {
        Health.Builder builder = Health.up();
        for (IocProperties.Sink.Artifact artifact : props.sink().artifacts()) {
            if (artifact.enabled()) {
                Path parent = Path.of(artifact.path()).toAbsolutePath().getParent();
                checkDirectory(parent, artifact.name() + "Dir", builder);
            }
        }
        return builder.build();
    }

    private void checkDirectory(Path path, String detail, Health.Builder builder) {
        if (path == null) {
            return;
        }
        try {
            Files.createDirectories(path);
        } catch (Exception e) {
            builder.down().withDetail(detail, path).withDetail(detail + "Error", e.getMessage());
            return;
        }
        if (!Files.isDirectory(path) || !Files.isWritable(path)) {
            builder.down().withDetail(detail, path);
        } else {
            builder.withDetail(detail, path);
        }
    }
}
