package com.iocextractor.application.dataframeimport.contract;

import com.iocextractor.application.dataframeimport.model.ImportArtifactRole;
import com.iocextractor.application.dataframeimport.model.ImportDuplicatePolicy;
import com.iocextractor.application.dataframeimport.model.ImportFormulaPolicy;
import com.iocextractor.application.dataframeimport.model.ImportMergePolicy;
import com.iocextractor.application.dataframeimport.model.ImportProcessingMode;
import com.iocextractor.application.dataframeimport.model.ImportRecordSeparator;
import com.iocextractor.application.dataframeimport.model.ImportRoutingPolicy;
import com.iocextractor.application.dataframeimport.model.ImportRowFailurePolicy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ProcessedRouteRequirementTest {
    @Test
    void missingProcessedRouteIsRejectedEvenWhenIntakeIsDisabled() {
        var result = compile(ImportProcessingMode.PROCESSED, null);
        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).anySatisfy(violation -> {
            assertThat(violation.path()).endsWith(".processed-route");
            assertThat(violation.message()).contains("requires an explicit named route");
        });
    }

    @Test
    void explicitRoutePreservesTheProcessedContractAndPinsItsFingerprint() {
        var first = compile(ImportProcessingMode.PROCESSED, route("original-import"));
        var second = compile(ImportProcessingMode.PROCESSED, route("host-import"));
        assertThat(first.violations()).isEmpty();
        assertThat(second.violations()).isEmpty();
        assertThat(first.catalogOrThrow().contracts().values().iterator().next().fingerprint())
                .isNotEqualTo(second.catalogOrThrow().contracts().values().iterator().next().fingerprint());
    }

    @Test
    void asIsRemainsAValidIndependentProductModeWithoutAnIocRoute() {
        assertThat(compile(ImportProcessingMode.AS_IS, null).violations()).isEmpty();
        assertThat(compile(ImportProcessingMode.AS_IS, route("original-import")).valid()).isFalse();
    }

    private DataframeImportCatalogDraft.ProcessedRoute route(String name) {
        return new DataframeImportCatalogDraft.ProcessedRoute(name,
                List.of(new DataframeImportCatalogDraft.RouteInput("ip_list", "ip")),
                List.of(new DataframeImportCatalogDraft.RouteOutput("ip_list", List.of("ip"))));
    }

    private DataframeImportCatalogCompilation compile(ImportProcessingMode mode,
                                                       DataframeImportCatalogDraft.ProcessedRoute route) {
        var artifact = new DataframeImportCatalogDraft.Artifact("ip_list", ImportArtifactRole.PRIMARY,
                "ip-row-v1", List.of("ip-v1"), null, mode == ImportProcessingMode.PROCESSED ? "name" : null, null, List.of(
                        new DataframeImportCatalogDraft.Column("ip", "ip", List.of(), null),
                        new DataframeImportCatalogDraft.Column("name", "name", List.of(), null)));
        var contract = new DataframeImportCatalogDraft.Contract("ip-feed", 1, "UTF-8",
                new DataframeImportCatalogDraft.Dialect(";", "\"", ImportRecordSeparator.CRLF_OR_LF, true, List.of("NULL")),
                new DataframeImportCatalogDraft.Recognition(List.of("ip", "name"), List.of(), List.of(), Map.of()),
                mode, ImportRoutingPolicy.TARGET_ONLY, ImportRowFailurePolicy.ACCEPT_VALID,
                ImportDuplicatePolicy.COALESCE, null, true, ImportFormulaPolicy.REJECT,
                ImportMergePolicy.FILL_MISSING, List.of(artifact), null, route);
        var schema = new DataframeImportCatalogEnvironment.ArtifactSchema(Set.of("ip", "name"),
                "ip-row-v1", Set.of("ip-v1"), Set.of(), false, Set.of("name"));
        return new DataframeImportCatalogCompiler().compile(
                new DataframeImportCatalogDraft(false, List.of(), List.of(), List.of(contract)),
                new DataframeImportCatalogEnvironment(Map.of("ip_list", schema), Set.of(), Set.of(), Set.of(), "policy"));
    }
}
