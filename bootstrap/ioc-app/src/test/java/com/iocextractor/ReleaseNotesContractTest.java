package com.iocextractor;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the mechanical release-notes source contract without pretending to
 * validate the meaning or completeness of maintainer-curated prose.
 */
class ReleaseNotesContractTest {

    private static final Pattern SOURCE_NAME = Pattern.compile(
            "v(\\d+\\.\\d+\\.\\d+(?:-rc\\.[1-9]\\d*)?)\\.md");
    private static final List<String> REQUIRED_SECTIONS = List.of(
            "## Highlights",
            "## Added",
            "## Changed",
            "## Fixed",
            "## Upgrade notes",
            "## Breaking or observable contract changes",
            "## Known issues",
            "## Verification",
            "## Artifacts and checksums");
    private static final List<String> UNFINISHED_MARKERS = List.of(
            "release-notes-template",
            "X.Y.Z",
            "vPREVIOUS",
            "<release highlight>",
            "<added capability",
            "<changed behavior",
            "<fixed behavior",
            "<pre-upgrade",
            "<configuration/data migration",
            "<supported procedure",
            "<issue ID",
            "<reactor, security");

    @Test
    void template_defines_the_complete_source_shape() {
        String template = read(releaseNotesDirectory().resolve("TEMPLATE.md"));

        assertThat(template).contains("release-notes-template");
        assertSectionsAppearInOrder(template);
        assertThat(template).contains("## Technical references");
    }

    @Test
    void committed_release_sources_follow_the_structural_contract() {
        List<Path> sources = releaseSources();

        assertThat(sources)
                .extracting(path -> path.getFileName().toString())
                .contains("v0.1.0.md", "v0.2.0.md");

        for (Path source : sources) {
            assertReleaseSource(source);
        }
    }

    private static void assertReleaseSource(Path source) {
        String fileName = source.getFileName().toString();
        Matcher matcher = SOURCE_NAME.matcher(fileName);
        assertThat(matcher.matches())
                .as("release-notes file name: %s", fileName)
                .isTrue();
        String version = matcher.group(1);
        String content = read(source);

        assertThat(content.lines().filter(line -> line.startsWith("# ")).toList())
                .as("single versioned document title: %s", fileName)
                .containsExactly("# IOC Extractor " + version);
        assertThat(content).doesNotContain(UNFINISHED_MARKERS.toArray(String[]::new));
        assertSectionsAppearInOrder(content);
        assertThat(section(content, "## Upgrade notes"))
                .as("upgrade notes must state the rollback disposition: %s", fileName)
                .containsIgnoringCase("rollback");
        assertThat(section(content, "## Artifacts and checksums"))
                .as("artifact identity must name its version: %s", fileName)
                .contains(version);

        if (content.contains("HISTORICAL_IMPORT")) {
            assertThat(fileName)
                    .as("only the release predating the source contract may be imported")
                    .isEqualTo("v0.1.0.md");
            assertThat(section(content, "## Artifacts and checksums"))
                    .contains("No SHA-256 checksum sidecar was published");
        } else {
            assertThat(content)
                    .as("current release sources must not retain template comments: %s", fileName)
                    .doesNotContain("<!--");
            assertThat(section(content, "## Artifacts and checksums"))
                    .contains(".sha256");
        }

        if (fileName.equals("v0.2.0.md")) {
            String normalizedUpgradeNotes =
                    section(content, "## Upgrade notes").replaceAll("\\s+", " ");
            assertThat(normalizedUpgradeNotes)
                    .as("0.2.0 must retain its explicit cross-layout transition contract")
                    .contains(
                            "An in-place upgrade of a 0.1.0 prefix is not supported",
                            "fresh, side-by-side installation",
                            "Re-ingest",
                            "Do not run 0.1.0 against the 0.2.0 SQLite databases");
        }
    }

    private static void assertSectionsAppearInOrder(String content) {
        int previous = -1;
        for (String section : REQUIRED_SECTIONS) {
            assertThat(content.lines().filter(section::equals).count())
                    .as("required release-notes section occurs once: %s", section)
                    .isEqualTo(1);
            int current = content.indexOf(section);
            assertThat(current)
                    .as("required release-notes section: %s", section)
                    .isGreaterThan(previous);
            previous = current;
        }
    }

    private static String section(String content, String heading) {
        int start = content.indexOf(heading);
        assertThat(start).as("section exists: %s", heading).isNotNegative();
        int next = content.indexOf("\n## ", start + heading.length());
        return next < 0 ? content.substring(start) : content.substring(start, next);
    }

    private static List<Path> releaseSources() {
        try (Stream<Path> files = Files.list(releaseNotesDirectory())) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> SOURCE_NAME.matcher(path.getFileName().toString()).matches())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path releaseNotesDirectory() {
        return reactorRoot().resolve(".github/release-notes");
    }

    private static Path reactorRoot() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            if (Files.isDirectory(directory.resolve("platform"))
                    && Files.isDirectory(directory.resolve("core"))
                    && Files.isDirectory(directory.resolve("adapters"))
                    && Files.isDirectory(directory.resolve("bootstrap"))) {
                return directory;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("reactor root not found from " + Path.of("").toAbsolutePath());
    }
}
