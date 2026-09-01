package com.iocextractor;

import com.iocextractor.application.tck.junit.IntegrationTest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.client.RestTestClient;

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
        "ioc.ingestion.dirs.inbox=target/test-mgmt/inbox",
        "ioc.ingestion.dirs.processing=target/test-mgmt/processing",
        "ioc.ingestion.dirs.done=target/test-mgmt/done",
        "ioc.ingestion.dirs.failed=target/test-mgmt/failed",
        "ioc.ingestion.ledger.path=target/test-mgmt/ledger",
        "spring.main.banner-mode=off"
})
@AutoConfigureRestTestClient
@IntegrationTest
class DaemonManagementEndpointIT {

    @Autowired
    RestTestClient rest;

    @Autowired
    ObjectProvider<BuildProperties> buildPropertiesProvider;

    @Test
    void health_endpoint_is_exposed_in_daemon_mode() {
        // Exposed and serving the health document. UP -> 200, partial DOWN -> 503;
        // either proves the endpoint is reachable (the point of ING-3).
        rest.get().uri("/actuator/health").exchange()
                .expectStatus().value(status -> assertThat(status).isIn(200, 503))
                .expectBody(String.class).value(body -> assertThat(body)
                        .contains("\"status\"")
                        .contains("\"jdbcStorage\"")
                        .contains("\"dataframeStorage\"")
                        .contains("\"artifactStorage\""));

        for (String component : new String[]{"jdbcStorage", "dataframeStorage", "artifactStorage"}) {
            rest.get().uri("/actuator/health/{component}", component).exchange()
                    .expectStatus().isOk()
                    .expectBody(String.class)
                    .value(body -> assertThat(body).contains("\"status\":\"UP\""));
        }
    }

    @Test
    void info_endpoint_exposes_embedded_build_identity() throws JsonProcessingException {
        var body = rest.get().uri("/actuator/info").exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).isNotNull();
        var build = new ObjectMapper().readTree(body).path("build");
        var buildProperties = buildPropertiesProvider.getIfAvailable();
        if (buildProperties == null) {
            assertThat(build.isMissingNode()).isTrue();
            return;
        }

        assertThat(build.path("group").asText()).isEqualTo(buildProperties.getGroup());
        assertThat(build.path("artifact").asText()).isEqualTo(buildProperties.getArtifact());
        assertThat(build.path("name").asText()).isEqualTo(buildProperties.getName());
        assertThat(build.path("version").asText()).isEqualTo(buildProperties.getVersion());
        assertThat(build.path("time").asText()).isEqualTo(buildProperties.getTime().toString());

        var commit = buildProperties.get("commit");
        if (commit == null) {
            assertThat(build.has("commit")).isFalse();
        } else {
            assertThat(build.path("commit").asText()).isEqualTo(commit);
        }
    }
}
