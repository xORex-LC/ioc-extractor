package com.iocextractor;

import com.iocextractor.application.port.in.ExtractIocsUseCase;
import com.iocextractor.application.port.in.ExtractionCommand;
import com.iocextractor.application.tck.junit.EndToEndTest;
import com.zaxxer.hikari.HikariDataSource;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** Qualifies a selected customer route through extraction, canonical commit and projection. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.main.banner-mode=off")
@ActiveProfiles({"golden", "customer-routes"})
@EndToEndTest
@Timeout(60)
class CustomerRoutingPipelineIT {
    private static final List<String> GOLDEN_CSV = List.of(
            "masks.csv", "ip-list.csv", "address-blacklist.csv", "hashes.csv", "IOC_aggregate.csv");

    @TempDir Path inputDir;

    @Autowired ExtractIocsUseCase useCase;

    @Autowired
    @Qualifier("dataframeStorageDataSource")
    HikariDataSource dataframe;

    @DynamicPropertySource
    static void freshDataframe(DynamicPropertyRegistry registry) throws Exception {
        Path database = Path.of("target/customer-routed-golden/ioc-dataframe.db");
        Files.createDirectories(database.getParent());
        Files.deleteIfExists(database);
        Files.deleteIfExists(Path.of(database + "-wal"));
        Files.deleteIfExists(Path.of(database + "-shm"));
        registry.add("golden.output-dir", () -> "target/customer-routed-golden");
    }

    @Test
    void selectedViewsCollapseHostRowsWhileOriginalBlacklistAndHashesRemain() throws Exception {
        Path source = inputDir.resolve("customer.html");
        Files.writeString(source, """
                <html><head><meta charset="utf-8"></head><body>
                <p>hxxps[:]//best-malware[.]com/troyan.exe</p>
                <p>https://best-malware.com/other.exe</p>
                <p>10.93.12.187:9090/clean-prometheus/noe-virus/true</p>
                <p>d41d8cd98f00b204e9800998ecf8427e</p>
                </body></html>
                """);

        useCase.extract(new ExtractionCommand("customer-route", source, false));

        assertThat(values("SELECT mask FROM masks ORDER BY mask"))
                .containsExactly("best-malware.com");
        assertThat(values("SELECT ip FROM ip_list ORDER BY ip"))
                .containsExactly("10.93.12.187");
        assertThat(values("SELECT forbidden_url FROM address_blacklist "
                + "WHERE forbidden_url IS NOT NULL ORDER BY forbidden_url"))
                .containsExactly("10.93.12.187:9090/clean-prometheus/noe-virus/true",
                        "https://best-malware.com/other.exe",
                        "https://best-malware.com/troyan.exe");
        assertThat(values("SELECT hash_md5 FROM hashes"))
                .containsExactly("D41D8CD98F00B204E9800998ECF8427E");
        assertThat(values("SELECT url_match FROM ioc_aggregate "
                + "WHERE url_match IS NOT NULL ORDER BY url_match"))
                .containsExactly("10.93.12.187:9090/clean-prometheus/noe-virus/true",
                        "https://best-malware.com/other.exe",
                        "https://best-malware.com/troyan.exe");
        for (String artifact : GOLDEN_CSV) {
            Path actual = Path.of("target/customer-routed-golden", artifact);
            assertThat(Files.readAllBytes(actual))
                    .as("public CSV artifact %s", artifact)
                    .isEqualTo(goldenBytes(artifact));
        }
    }

    private byte[] goldenBytes(String resource) throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/customer-routes/" + resource)) {
            if (input == null) {
                throw new IllegalStateException("Missing customer route fixture: " + resource);
            }
            String logical = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            return logical.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);
        }
    }

    private List<String> values(String sql) throws Exception {
        List<String> result = new ArrayList<>();
        try (Connection connection = dataframe.getConnection();
             var statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                result.add(rows.getString(1));
            }
        }
        return result;
    }
}
