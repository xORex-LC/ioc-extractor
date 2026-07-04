package com.iocextractor;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Enforces the "README in every module" convention (docs/CONVENTIONS.md) as a
 * failing test: every Maven module (a directory carrying a {@code pom.xml},
 * excluding the reactor root) must ship a {@code README.md}. Documentation rots
 * silently; this keeps the module reference axis honest at build time, the same
 * way ArchUnit keeps the dependency axis honest.
 */
class DocumentationConventionTest {

    @Test
    void every_maven_module_has_a_readme() {
        Path root = reactorRoot();
        List<String> missing;
        try (Stream<Path> tree = Files.walk(root)) {
            missing = tree
                    .filter(Files::isDirectory)
                    .filter(dir -> !dir.equals(root))
                    .filter(dir -> !containsSegment(root.relativize(dir), "target"))
                    .filter(dir -> Files.exists(dir.resolve("pom.xml")))
                    .filter(dir -> !Files.exists(dir.resolve("README.md")))
                    .map(dir -> root.relativize(dir).toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(missing)
                .as("Maven modules missing a README.md (see docs/CONVENTIONS.md)")
                .isEmpty();
    }

    private static boolean containsSegment(Path relative, String segment) {
        for (Path part : relative) {
            if (part.toString().equals(segment)) {
                return true;
            }
        }
        return false;
    }

    /** Walks up from the test working directory to the reactor root (holds all module groups). */
    private static Path reactorRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("platform"))
                    && Files.isDirectory(dir.resolve("core"))
                    && Files.isDirectory(dir.resolve("adapters"))
                    && Files.isDirectory(dir.resolve("bootstrap"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("reactor root not found from " + Path.of("").toAbsolutePath());
    }
}
