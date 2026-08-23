package com.iocextractor.application.dataframeimport.mapping;

import com.iocextractor.application.dataframeimport.contract.DataframeImportCatalogDraft;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImportHeaderPlanTest {

    @Test
    void resolves_aliases_independently_of_column_order_and_discards_declared_ignored_columns() {
        var recognition = new DataframeImportCatalogDraft.Recognition(
                List.of("ip", "score"), List.of("description"), List.of("comment"),
                Map.of("IP Address", "ip"));

        ImportHeaderPlan plan = ImportHeaderPlan.compile(
                List.of("score", "comment", "IP Address"), recognition);

        assertThat(plan.values(3, List.of("10", "safe-to-ignore", "192.0.2.1")::get))
                .containsExactly(
                        Map.entry("score", "10"),
                        Map.entry("ip", "192.0.2.1"));
    }

    @Test
    void fails_closed_for_missing_unexpected_or_alias_colliding_headers_without_echoing_values() {
        var recognition = new DataframeImportCatalogDraft.Recognition(
                List.of("ip", "score"), List.of(), List.of(), Map.of("IP Address", "ip"));

        assertThatThrownBy(() -> ImportHeaderPlan.compile(
                List.of("ip", "IP Address", "operator-secret"), recognition))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing=1, unexpected=1, duplicate=1")
                .hasMessageNotContaining("operator-secret");
    }
}
