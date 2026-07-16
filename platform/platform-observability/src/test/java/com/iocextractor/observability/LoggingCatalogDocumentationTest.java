package com.iocextractor.observability;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LoggingCatalogDocumentationTest {

    @Test
    void committed_catalog_document_matches_generated_catalog() throws Exception {
        var catalog = repoRoot().resolve("docs/LOGGING-CATALOG.md");
        var generated = generatedMarkdown();
        if (Boolean.getBoolean("ioc.docs.update")) {
            Files.writeString(catalog, generated, StandardCharsets.UTF_8);
        }
        var committed = Files.readString(catalog, StandardCharsets.UTF_8);

        assertThat(normalize(committed))
                .withFailMessage("Generated catalog is stale; run with -Dioc.docs.update=true")
                .isEqualTo(normalize(generated));
    }

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
        builder.append("# Logging Catalog\n\n");
        builder.append("Generated from `EventAction` and `LogField`.\n\n");
        builder.append("## Actions\n\n");
        builder.append("| Action | Area | When |\n");
        builder.append("|---|---|---|\n");
        for (var action : EventAction.values()) {
            builder.append("| `%s` | %s | %s |%n".formatted(
                    action.value(), action.area(), escapePipes(action.description())));
        }
        builder.append("\n## Fields\n\n");
        builder.append("| Field | Namespace | JSON type | Description |\n");
        builder.append("|---|---|---|---|\n");
        for (var field : LogField.values()) {
            builder.append("| `%s` | %s | `%s` | %s |%n".formatted(
                    field.key(), namespace(field), field.valueType().name().toLowerCase(),
                    escapePipes(field.description())));
        }
        return builder.toString();
    }

    private String namespace(LogField field) {
        return field.key().startsWith("ioc.") ? "ioc" : "ecs";
    }

    private String escapePipes(String value) {
        return value.replace("|", "\\|");
    }

    private String normalize(String content) {
        return content.replace("\r\n", "\n").stripTrailing();
    }
}
