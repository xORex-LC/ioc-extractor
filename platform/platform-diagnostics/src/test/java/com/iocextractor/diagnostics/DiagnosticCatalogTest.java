package com.iocextractor.diagnostics;

import com.iocextractor.diagnostics.catalog.DiagnosticCatalogs;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnosticCatalogTest {

    @Test
    void severity_set_matches_the_published_contract() {
        assertThat(DiagnosticSeverity.values())
                .containsExactly(
                        DiagnosticSeverity.TRACE,
                        DiagnosticSeverity.DEBUG,
                        DiagnosticSeverity.INFO,
                        DiagnosticSeverity.WARN,
                        DiagnosticSeverity.ERROR,
                        DiagnosticSeverity.FATAL);
    }

    @Test
    void catalog_entries_are_stable_and_unique() {
        var entries = DiagnosticCatalogs.entries();

        assertThat(entries).hasSize(27);
        assertThat(entries).extracting("id").doesNotHaveDuplicates();
        assertThat(entries).extracting("messageKey").doesNotHaveDuplicates();
        assertThat(entries).allSatisfy(entry -> {
            assertThat(entry.id()).matches("[A-Z]+\\.[A-Z0-9_]+");
            assertThat(entry.id()).startsWith(entry.category().name() + ".");
            assertThat(entry.messageKey()).isNotBlank();
            assertThat(entry.defaultMessageTemplate()).isNotBlank();
        });
    }

    @Test
    void all_diagnostic_code_enums_are_registered_in_the_aggregate_catalog() throws Exception {
        var registered = new HashSet<>(DiagnosticCatalogs.all());

        assertThat(discoverDiagnosticCodeConstants())
                .isEqualTo(registered);
    }

    private Set<DiagnosticCode> discoverDiagnosticCodeConstants() throws Exception {
        var packagePath = "com/iocextractor/diagnostics/codes";
        var classLoader = Thread.currentThread().getContextClassLoader();
        var resources = classLoader.getResources(packagePath);
        var discovered = new HashSet<DiagnosticCode>();

        while (resources.hasMoreElements()) {
            var resource = resources.nextElement();
            assertThat(resource.getProtocol()).isEqualTo("file");
            var directory = Path.of(URLDecoder.decode(resource.getPath(), StandardCharsets.UTF_8));
            try (var files = Files.list(directory)) {
                var classNames = files
                        .map(Path::getFileName)
                        .map(Path::toString)
                        .filter(name -> name.endsWith(".class"))
                        .filter(name -> !name.equals("package-info.class"))
                        .map(name -> name.substring(0, name.length() - ".class".length()))
                        .toList();

                for (var className : classNames) {
                    var type = Class.forName("com.iocextractor.diagnostics.codes." + className);
                    if (DiagnosticCode.class.isAssignableFrom(type) && type.isEnum()) {
                        for (var constant : type.getEnumConstants()) {
                            discovered.add((DiagnosticCode) constant);
                        }
                    }
                }
            }
        }

        return discovered;
    }
}
