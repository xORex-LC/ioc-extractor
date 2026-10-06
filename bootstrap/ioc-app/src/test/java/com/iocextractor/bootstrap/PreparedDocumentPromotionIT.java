package com.iocextractor.bootstrap;

import com.iocextractor.application.port.out.artifact.ArtifactIdBaseline;
import com.iocextractor.application.artifact.NoopArtifactProjection;
import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.observation.ObservationOrigin;
import com.iocextractor.application.port.in.ExtractionCommand;
import com.iocextractor.application.port.out.observation.ObservationRegistrationStore;
import com.iocextractor.application.port.out.observability.PipelineDecisionTracer;
import com.iocextractor.application.service.IocExtractionServiceFactory;
import com.iocextractor.application.tck.junit.EndToEndTest;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies canonical winners when Router preparation completes out of admission order. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.main.banner-mode=off")
@ActiveProfiles("golden")
@EndToEndTest
@Timeout(60)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PreparedDocumentPromotionIT {
    @TempDir static Path root;

    @DynamicPropertySource
    static void isolateStorage(DynamicPropertyRegistry registry) {
        registry.add("golden.output-dir", () -> root.toAbsolutePath().toString());
        registry.add("ioc.processing.workspace.directory", () -> root.resolve("workspace").toString());
    }

    @Autowired AppConfig config;
    @Autowired IocProperties properties;
    @Autowired ArtifactIdBaseline baseline;
    @Autowired PipelineDecisionTracer tracer;
    @Autowired IocExtractionServiceFactory factory;
    @Autowired ObservationRegistrationStore registrations;
    @Autowired Clock clock;
    @Autowired @Qualifier("dataframeStorageDataSource") HikariDataSource dataframe;

    @Test
    void earlierAdmissionKeepsWholeRowWhileLatestRegisteredFieldUsesTheNewerRank() throws Exception {
        var earlier = registrations.registerNew(new ObservationId("earlier"), ObservationOrigin.DOCUMENT);
        var later = registrations.registerNew(new ObservationId("later"), ObservationOrigin.DOCUMENT);
        var preparers = config.artifactPreparers(config.artifactDefinitions(properties, baseline), null, clock, tracer);
        var extraction = factory.create(preparers, NoopArtifactProjection.INSTANCE);
        Path oldDocument = document("earlier.html", "БИБ-100");
        Path newDocument = document("later.html", "БИБ-200");

        // The newer sealed result waits without IDs or canonical effects.
        try (var second = extraction.prepare(new ExtractionCommand("later-run", newDocument, false, null, later));
             var first = extraction.prepare(new ExtractionCommand("earlier-run", oldDocument, false, null, earlier))) {
            assertThat(scalar("SELECT COUNT(*) FROM masks")).isEqualTo("0");
            assertThat(scalar("SELECT COUNT(*) FROM ioc_aggregate")).isEqualTo("0");
            assertThat(first.promote().writtenPerArtifact()).containsEntry("masks", 1);
            assertThat(second.promote().writtenPerArtifact()).containsEntry("masks", 0);
        }

        assertThat(scalar("SELECT COUNT(*) FROM masks")).isEqualTo("1");
        assertThat(scalar("SELECT source FROM masks")).isEqualTo("БИБ-100");
        assertThat(scalar("SELECT COUNT(*) FROM ioc_aggregate")).isEqualTo("1");
        assertThat(scalar("SELECT name FROM ioc_aggregate")).isEqualTo("БИБ-200");
        assertThat(scalar("SELECT host_match FROM ioc_aggregate")).isEqualTo("prepared-order.example.test");
    }

    private Path document(String name, String section) throws Exception {
        Path path = root.resolve(name);
        Files.writeString(path, "<html><head><meta charset=\"utf-8\"></head><body><h2>"
                + section + "</h2><p>prepared-order.example.test</p></body></html>");
        return path;
    }

    private String scalar(String sql) throws Exception {
        try (var connection = dataframe.getConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            String value = rows.getString(1);
            assertThat(rows.next()).isFalse();
            return value;
        }
    }
}
