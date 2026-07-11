package com.iocextractor;

import com.iocextractor.diagnostics.DiagnosticCode;
import com.iocextractor.diagnostics.catalog.DiagnosticCatalogs;
import com.iocextractor.observability.EventAction;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static java.util.stream.Collectors.toMap;

/**
 * Prevents catalog constants from becoming silently dead declarations.
 *
 * <p>This test intentionally verifies a production bytecode reference, not a
 * real call to {@code DiagnosticSink.emit}. Compiler-generated enum switch
 * maps count as references. Reflection-based producers would not be seen, but
 * the project does not use or permit that convention for catalog emission.
 */
class CatalogReferenceRatchetTest {

    /**
     * Known unreferenced diagnostic contracts that OBS-D1 will burn down.
     * Config codes are deliberately retained here until the D1 catalog review
     * decides whether ADR 0016 made them obsolete.
     */
    private static final Set<String> ALLOWED_UNREFERENCED_DIAGNOSTIC_IDS = Set.of(
            "CLASSIFY.AMBIGUOUS_MATCH",
            "CLASSIFY.UNSUPPORTED_INDICATOR_TYPE",
            "CONFIG.INVALID_PROPERTY",
            "CONFIG.UNKNOWN_POLICY",
            "EXTRACTION.AMBIGUOUS_VALUE",
            "EXTRACTION.INDICATOR_SKIPPED",
            "EXTRACTION.PATTERN_INVALID",
            "INGEST.CLAIM_FAILED",
            "INGEST.LEDGER_WRITE_FAILED",
            "INGEST.RECOVERY_FAILED",
            "PIPELINE.ITEM_SKIPPED",
            "PIPELINE.STAGE_FAILED",
            "SINK.ROW_MAPPING_FAILED",
            "SINK.WRITE_FAILED",
            "SOURCE.EMPTY_TEXT",
            "SOURCE.READ_FAILED",
            "SOURCE.UNSUPPORTED_FORMAT",
            "SYNC.AUTH_FAILED",
            "SYNC.CREDENTIAL_MISSING",
            "SYNC.ENDPOINT_UNKNOWN",
            "SYNC.ENDPOINT_UNREACHABLE",
            "SYNC.PERMISSION_DENIED",
            "SYNC.REMOTE_NOT_FOUND",
            "SYNC.TRANSPORT_TRANSIENT");

    @Test
    void every_catalog_constant_is_referenced_or_explicitly_allowlisted() {
        var references = productionFieldReferences();

        assertThat(unreferencedDiagnosticIds(references))
                .as("new diagnostic code without a production reference must be emitted or allowlisted for OBS-D1")
                .isEqualTo(ALLOWED_UNREFERENCED_DIAGNOSTIC_IDS);
        assertThat(unreferencedEventActions(references))
                .as("every event action must have a production reference")
                .isEmpty();
    }

    private Set<FieldReference> productionFieldReferences() {
        var imported = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.iocextractor");
        var references = new LinkedHashSet<FieldReference>();
        imported.stream()
                .flatMap(javaClass -> javaClass.getFieldAccessesFromSelf().stream())
                .filter(this::isExternalCatalogAccess)
                .map(this::fieldReference)
                .forEach(references::add);
        return references;
    }

    private boolean isExternalCatalogAccess(JavaFieldAccess access) {
        return !access.getOriginOwner().equals(access.getTarget().getOwner())
                && !access.getOriginOwner().getName().equals(DiagnosticCatalogs.class.getName());
    }

    private Set<String> unreferencedDiagnosticIds(Set<FieldReference> references) {
        var idsByField = DiagnosticCatalogs.all().stream()
                .collect(toMap(this::fieldReference, DiagnosticCode::id));
        var unreferenced = new LinkedHashSet<>(idsByField.values());
        references.stream()
                .map(idsByField::get)
                .filter(id -> id != null)
                .forEach(unreferenced::remove);
        return unreferenced;
    }

    private Set<String> unreferencedEventActions(Set<FieldReference> references) {
        Map<FieldReference, String> valuesByField = java.util.Arrays.stream(EventAction.values())
                .collect(toMap(this::fieldReference, EventAction::value));
        var unreferenced = new LinkedHashSet<>(valuesByField.values());
        references.stream()
                .map(valuesByField::get)
                .filter(value -> value != null)
                .forEach(unreferenced::remove);
        return unreferenced;
    }

    private FieldReference fieldReference(DiagnosticCode code) {
        return new FieldReference(code.getClass().getName(), ((Enum<?>) code).name());
    }

    private FieldReference fieldReference(EventAction action) {
        return new FieldReference(EventAction.class.getName(), action.name());
    }

    private FieldReference fieldReference(JavaFieldAccess access) {
        return new FieldReference(access.getTarget().getOwner().getName(), access.getTarget().getName());
    }

    private record FieldReference(String ownerName, String fieldName) {
    }
}
