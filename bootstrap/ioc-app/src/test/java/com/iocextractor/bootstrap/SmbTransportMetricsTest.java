package com.iocextractor.bootstrap;

import com.iocextractor.adapter.out.transport.smb.SmbTransportTelemetry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SmbTransportMetricsTest {

    @Test
    void registersBoundedEndpointAndRoleDimensions() {
        var registry = new SimpleMeterRegistry();

        new SmbTransportMetrics(
                registry, new SmbTransportTelemetry(), Map.of("primary", 3));

        assertThat(registry.find("ioc.smb.sessions.active")
                .tags("endpoint", "primary", "role", "pooled_transport")
                .gauge()).isNotNull();
        assertThat(registry.find("ioc.smb.session.opens")
                .tags("endpoint", "primary", "role", "change_notify", "outcome", "failure")
                .functionCounter()).isNotNull();
        assertThat(registry.getMeters())
                .flatExtracting(meter -> meter.getId().getTags())
                .extracting(tag -> tag.getKey())
                .doesNotContain("host", "share", "path", "username", "source");
    }
}
