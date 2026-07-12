package com.iocextractor.diagnostics;

import com.iocextractor.diagnostics.catalog.DiagnosticCatalogEntry;
import com.iocextractor.diagnostics.catalog.DiagnosticCatalogs;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnosticCatalogDocumentationTest {

    @Test
    void committed_catalog_document_matches_generated_catalog() throws Exception {
        var catalog = repoRoot().resolve("docs/DIAGNOSTICS-CATALOG.md");
        var generated = generatedMarkdown();
        if (Boolean.getBoolean("ioc.docs.update")) {
            Files.writeString(catalog, generated, StandardCharsets.UTF_8);
        }
        var committed = Files.readString(catalog, StandardCharsets.UTF_8);

        assertThat(normalize(committed))
                .withFailMessage("Generated catalog is stale; run with -Dioc.docs.update=true")
                .isEqualTo(normalize(generated));
    }

    /**
     * Resolves a repo-root-relative file by walking up from the working
     * directory, so the test works both in a flat layout and inside a Maven
     * module (where the working directory is the module, not the repo root).
     */
    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isRegularFile(dir.resolve("pom.xml")) && Files.isDirectory(dir.resolve("docs"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("Cannot locate repository root from " + Path.of("").toAbsolutePath());
    }

    private String generatedMarkdown() {
        var builder = new StringBuilder();
        builder.append("# Diagnostic Catalog\n\n");
        builder.append("Generated from `DiagnosticCatalogs`.\n\n");
        builder.append("| Code | Category | Severity | Impact | Message Key | Default Template |\n");
        builder.append("|---|---|---|---|---|---|\n");
        DiagnosticCatalogs.entries().stream()
                .map(this::row)
                .forEach(builder::append);
        return builder.toString();
    }

    private String row(DiagnosticCatalogEntry entry) {
        return "| `%s` | %s | %s | %s | `%s` | %s |%n".formatted(
                entry.id(),
                entry.category(),
                entry.defaultSeverity(),
                entry.impact(),
                entry.messageKey(),
                escapePipes(entry.defaultMessageTemplate()));
    }

    private String escapePipes(String value) {
        return value.replace("|", "\\|");
    }

    private String normalize(String content) {
        return content.replace("\r\n", "\n").stripTrailing();
    }
}
