package com.iocextractor.diagnostics;

import com.iocextractor.diagnostics.catalog.DiagnosticCatalogEntry;
import com.iocextractor.diagnostics.catalog.DiagnosticCatalogs;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnosticCatalogDocumentationTest {

    @Test
    void committed_catalog_document_matches_generated_catalog() throws Exception {
        var committed = Files.readString(Path.of("docs/diagnostic-catalog.md"));

        assertThat(normalize(committed)).isEqualTo(normalize(generatedMarkdown()));
    }

    private String generatedMarkdown() {
        var builder = new StringBuilder();
        builder.append("# Diagnostic Catalog\n\n");
        builder.append("Generated from `DiagnosticCatalogs`.\n\n");
        builder.append("| Code | Category | Severity | Message Key | Default Template |\n");
        builder.append("|---|---|---|---|---|\n");
        DiagnosticCatalogs.entries().stream()
                .map(this::row)
                .forEach(builder::append);
        return builder.toString();
    }

    private String row(DiagnosticCatalogEntry entry) {
        return "| `%s` | %s | %s | `%s` | %s |%n".formatted(
                entry.id(),
                entry.category(),
                entry.defaultSeverity(),
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
