package com.iocextractor;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ING-3: in daemon mode the actuator health surface is exposed over HTTP. Uses a
 * real server on a random port (so it never collides with the configured 8081),
 * which also verifies the actuator health surface is wired in daemon mode.
 *
 * <p>The {@code spring.main.web-application-type=servlet} property is set explicitly
 * here because {@code @SpringBootTest} inlined properties are applied after
 * environment post-processors run, so {@code DaemonWebEnvironmentPostProcessor}
 * (which gates on {@code ioc.runtime.mode}) cannot see them; at real launch the
 * mode is a command-line arg visible to the post-processor. The gating itself is
 * covered by {@code DaemonWebEnvironmentPostProcessorTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "ioc.runtime.mode=daemon",
        "spring.main.web-application-type=servlet",
        "ioc.aggregation.enabled=false",
        "ioc.lookup.path=target/test-mgmt/no-lookup.csv",
        "ioc.ingestion.dirs.inbox=target/test-mgmt/inbox",
        "ioc.ingestion.dirs.processing=target/test-mgmt/processing",
        "ioc.ingestion.dirs.done=target/test-mgmt/done",
        "ioc.ingestion.dirs.failed=target/test-mgmt/failed",
        "ioc.ingestion.ledger.path=target/test-mgmt/ledger",
        "ioc.ingestion.output.partitions-dir=target/test-mgmt/partitions",
        "spring.main.banner-mode=off"
})
class DaemonManagementEndpointTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Test
    void health_endpoint_is_exposed_in_daemon_mode() {
        ResponseEntity<String> response = rest.getForEntity(
                "http://localhost:" + port + "/actuator/health", String.class);

        // Exposed and serving the health document. UP -> 200, partial DOWN -> 503;
        // either proves the endpoint is reachable (the point of ING-3).
        assertThat(response.getStatusCode().value()).isIn(200, 503);
        assertThat(response.getBody()).contains("\"status\"");
    }
}
