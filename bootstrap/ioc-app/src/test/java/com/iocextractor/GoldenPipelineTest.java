package com.iocextractor;

import com.iocextractor.application.port.in.ExtractIocsUseCase;
import com.iocextractor.application.port.in.ExtractionCommand;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end golden regression. Runs the real pipeline over a synthetic fixture
 * covering every classification bucket (variants 1–4, bare IP, onion,
 * telegram, hashes) and compares the generated artifacts to committed golden
 * files.
 *
 * <p>Isolated: the fixture is a test resource, output is redirected to {@code target/}
 * (not {@code dataframe/}), and the lookup points at a non-existent file.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "spring.main.banner-mode=off")
@ActiveProfiles("golden")
class GoldenPipelineTest {

    @Autowired
    ExtractIocsUseCase useCase;

    @DynamicPropertySource
    static void pristineOutput(DynamicPropertyRegistry registry) {
        Path dir = Path.of("target/golden");
        if (Files.notExists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void pipeline_output_matches_golden() throws Exception {
        useCase.extract(new ExtractionCommand(Path.of("src/test/resources/golden/source.html"), false));

        assertThat(normalize(Files.readString(Path.of("target/golden/masks.csv"))))
                .isEqualTo(goldenResource("golden/expected-masks.csv"));
        assertThat(normalize(Files.readString(Path.of("target/golden/ip-list.csv"))))
                .isEqualTo(goldenResource("golden/expected-ip-list.csv"));
        assertThat(normalize(Files.readString(Path.of("target/golden/address-blacklist.csv"))))
                .isEqualTo(goldenResource("golden/expected-address-blacklist.csv"));
        assertThat(normalize(Files.readString(Path.of("target/golden/hashes.csv"))))
                .isEqualTo(goldenResource("golden/expected-hashes.csv"));
    }

    private String goldenResource(String resource) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            return normalize(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /** Line-ending and trailing-whitespace insensitive comparison. */
    private String normalize(String content) {
        return content.replace("\r\n", "\n").strip();
    }
}
