package com.iocextractor.bootstrap;

import com.iocextractor.adapter.processing.camel.runtime.CamelRouteRuntime;
import com.iocextractor.application.dataframeimport.contract.DataframeImportCatalog;
import com.iocextractor.application.dataframeimport.mapping.ImportHeaderPlan;
import com.iocextractor.application.dataframeimport.model.ImportClaimReservation;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryState;
import com.iocextractor.application.dataframeimport.model.ImportDuplicatePolicy;
import com.iocextractor.application.dataframeimport.model.ImportSourceId;
import com.iocextractor.application.dataframeimport.model.ImportTerminalOutcome;
import com.iocextractor.application.port.in.dataframeimport.AdmitDataframeImportCommand;
import com.iocextractor.application.port.in.dataframeimport.AdmitDataframeImportUseCase;
import com.iocextractor.application.port.in.dataframeimport.ProcessNextDataframeImportUseCase;
import com.iocextractor.application.port.out.dataframeimport.DelimitedHeaderReadCommand;
import com.iocextractor.application.port.out.dataframeimport.DelimitedRecordReader;
import com.iocextractor.application.port.out.dataframeimport.ImportCommitEvidenceStore;
import com.iocextractor.application.port.out.dataframeimport.ImportDeliveryLedger;
import com.iocextractor.application.port.out.dataframeimport.ImportWorkspace;
import com.iocextractor.application.port.out.dataframeimport.ManagedImportSourceLifecycle;
import com.iocextractor.application.port.out.dataframeimport.ProcessedImportRowPreparer;
import com.iocextractor.application.tck.junit.EndToEndTest;
import com.iocextractor.domain.extract.IndicatorExtractor;
import com.iocextractor.domain.refang.Refanger;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** Qualifies the YAML-selected import with production Spring bindings and durable recovery. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "spring.main.banner-mode=off")
@ActiveProfiles({"golden", "selected-import-production"})
@EndToEndTest
@Timeout(90)
class RouterSelectedImportDeliveryIT {
    private static final ImportSourceId SOURCE = new ImportSourceId("local-hosts");
    private static final ImportDeliveryId DELIVERY = new ImportDeliveryId("selected-production-import");

    @TempDir static Path root;

    @DynamicPropertySource
    static void isolateStorage(DynamicPropertyRegistry registry) {
        registry.add("selected.import.root", () -> root.toAbsolutePath().toString());
        registry.add("golden.output-dir", () -> root.toAbsolutePath().toString());
    }

    @Autowired ManagedDataframeImportRuntime runtime;
    @Autowired ConfigRegistryPreflight preflight;
    @Autowired ProcessingPlanBindings plans;
    @Autowired DataframeImportCatalog catalog;
    @Autowired ProcessedImportRowPreparer preparer;
    @Autowired Refanger refanger;
    @Autowired IndicatorExtractor extractor;
    @Autowired CamelRouteRuntime camel;
    @Autowired @Qualifier("managedImportSourceLifecycle") ManagedImportSourceLifecycle sources;
    @Autowired AdmitDataframeImportUseCase admission;
    @Autowired DelimitedRecordReader reader;
    @Autowired ImportWorkspace workspace;
    @Autowired ImportDeliveryLedger ledger;
    @Autowired ImportCommitEvidenceStore commits;
    @Autowired @Qualifier("processNextDataframeImportUseCase")
    ProcessNextDataframeImportUseCase processor;
    @Autowired @Qualifier("dataframeStorageDataSource") HikariDataSource dataframe;
    @Autowired @Qualifier("serviceStorageDataSource") HikariDataSource service;
    @Autowired Clock clock;

    @Test
    void configuredCsvRouteCoalescesAndFinalizesOnlyFromCanonicalReceipt() throws Exception {
        // Stop background discovery so every durable transition below is deterministic.
        runtime.close();
        assertThat(preflight.processingPlanBindings()).isEqualTo(plans);
        assertThat(plans.plans()).containsKey("imported-hosts");
        assertThat(catalog.contracts()).containsKey(
                new com.iocextractor.application.dataframeimport.model.ImportContractId("host-masks-v1"));
        assertThat(preparer).isInstanceOf(SelectedProcessedImportRowPreparer.class);
        assertThat(refanger).isNotNull();
        assertThat(extractor).isNotNull();
        assertThat(camel).isNotNull();
        assertThat(service).isNotNull();

        Path inbox = Files.createDirectories(root.resolve("inbox"));
        String csv = "ioc;source\nhttps://EVIL.example/one;Feed Alpha\n"
                + "http://evil.example/two;Feed Alpha\n";
        Files.writeString(inbox.resolve("hosts.csv"), csv, StandardCharsets.UTF_8);
        var candidate = sources.detect(SOURCE, clock.instant()).getFirst();
        var admitted = admission.admit(new AdmitDataframeImportCommand(
                new ImportClaimReservation(DELIVERY, SOURCE, candidate.candidateToken(), clock.instant())));
        assertThat(admitted.delivery().state()).isEqualTo(ImportDeliveryState.SNAPSHOT_PINNED);
        var snapshot = admitted.delivery().snapshot().orElseThrow();
        var contract = catalog.contracts().values().iterator().next();
        assertThat(contract.definition().duplicatePolicy()).isEqualTo(ImportDuplicatePolicy.COALESCE);
        var headers = reader.readHeader(new DelimitedHeaderReadCommand(snapshot.reference(),
                contract.definition().charset(), contract.dialect(),
                com.iocextractor.application.dataframeimport.model.ImportWorkspaceLimits.defaults().inputLimits()));
        assertThat(headers).containsExactly("ioc", "source");
        ImportHeaderPlan.compile(headers, contract.definition().recognition());
        assertThat(processor.processNext().workPerformed()).isTrue();
        var staged = ledger.find(DELIVERY).orElseThrow();
        assertThat(staged.state()).isEqualTo(ImportDeliveryState.STAGED);
        var stage = staged.stage().orElseThrow();
        var pinnedContract = staged.contract().orElseThrow();

        assertThat(stage.sourceRows()).isEqualTo(2);
        assertThat(stage.acceptedRows()).isOne();
        assertThat(stage.rejectedRows()).isZero();
        assertCoalescedStage();
        assertThat(processor.processNext().workPerformed()).isTrue();
        assertThat(ledger.find(DELIVERY).orElseThrow().state())
                .isEqualTo(ImportDeliveryState.CANONICAL_COMMITTED);
        var receipt = commits.find(DELIVERY).orElseThrow();
        assertThat(receipt.acceptedRows()).isOne();
        assertThat(receipt.rejectedRows()).isZero();
        assertThat(receipt.publicMutations()).isOne();
        assertThat(receipt.affectedArtifacts()).containsExactly("masks");
        assertThat(receipt.terminalOutcome()).isEqualTo(ImportTerminalOutcome.SUCCEEDED);
        assertCanonicalRow();

        // A post-commit crash can lose the stage. The durable receipt still finalizes
        // the delivery; the pinned CSV stays available for terminal archiving.
        workspace.discard(DELIVERY);
        assertThat(workspace.adoptSealed(DELIVERY, snapshot, pinnedContract)).isEmpty();
        assertThat(processor.processNext().workPerformed()).isTrue();
        assertThat(ledger.find(DELIVERY).orElseThrow().state())
                .isEqualTo(ImportDeliveryState.TERMINAL);
        assertThat(commits.find(DELIVERY)).contains(receipt);
        assertThat(workspace.adoptSealed(DELIVERY, snapshot, pinnedContract)).isEmpty();
        assertCanonicalRow();
        try (var terminal = Files.list(root.resolve("terminal"))) {
            var unit = terminal.toList();
            assertThat(unit).hasSize(1);
            assertThat(Files.readString(unit.getFirst().resolve("source.csv"))).isEqualTo(csv);
            assertThat(Files.readString(unit.getFirst().resolve("report.json")))
                    .contains("\"outcome\":\"SUCCEEDED\"", "\"acceptedRows\":1",
                            "\"rejectedRows\":0", "\"affectedArtifacts\":[\"masks\"]");
        }
        try (Connection connection = service.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT state FROM import_delivery WHERE delivery_id = ?")) {
            statement.setString(1, DELIVERY.value());
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("TERMINAL");
                assertThat(rows.next()).isFalse();
            }
        }
    }

    private void assertCanonicalRow() throws Exception {
        try (Connection connection = dataframe.getConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery(
                     "SELECT mask, url_match, host_match, source, row_key FROM masks")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString("mask")).isEqualTo("evil.example");
            assertThat(rows.getString("url_match")).isEqualTo("u:hAS");
            assertThat(rows.getString("host_match")).isEqualTo("h:dAS");
            assertThat(rows.getString("source")).isEqualTo("Feed Alpha");
            assertThat(rows.getString("row_key"))
                    .isEqualTo("9aaee4061740463c0c7851a4e5900e7b82728a77261e3a9a5a51af43479850bb");
            assertThat(rows.next()).isFalse();
        }
    }

    private void assertCoalescedStage() throws Exception {
        Path sealed;
        try (var files = Files.list(root.resolve("staging"))) {
            var stages = files.filter(file -> file.getFileName().toString().endsWith(".sealed.db")).toList();
            assertThat(stages).hasSize(1);
            sealed = stages.getFirst();
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sealed);
             var statement = connection.createStatement();
             var rows = statement.executeQuery(
                     "SELECT status FROM stage_input_row ORDER BY source_row_number")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo("ACCEPTED");
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo("COALESCED");
            assertThat(rows.next()).isFalse();
        }
    }

}
