package com.iocextractor.application.dataframeimport;

import com.iocextractor.application.artifact.ArtifactIdentityDefinition;
import com.iocextractor.application.artifact.CanonicalArtifactKeyResolver;
import com.iocextractor.application.dataframeimport.contract.CompiledDataframeImportContract;
import com.iocextractor.application.dataframeimport.contract.DataframeImportCatalog;
import com.iocextractor.application.dataframeimport.contract.DataframeImportCatalogDraft;
import com.iocextractor.application.dataframeimport.contract.DataframeImportRecognizer;
import com.iocextractor.application.dataframeimport.mapping.DataframeImportRowMapper;
import com.iocextractor.application.dataframeimport.model.DelimitedDialect;
import com.iocextractor.application.dataframeimport.model.ImportArtifactRole;
import com.iocextractor.application.dataframeimport.model.ImportCatalogFingerprint;
import com.iocextractor.application.dataframeimport.model.ImportContractFingerprint;
import com.iocextractor.application.dataframeimport.model.ImportContractId;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportDuplicatePolicy;
import com.iocextractor.application.dataframeimport.model.ImportFormulaPolicy;
import com.iocextractor.application.dataframeimport.model.ImportLogicalRow;
import com.iocextractor.application.dataframeimport.model.ImportMergePolicy;
import com.iocextractor.application.dataframeimport.model.ImportProcessingMode;
import com.iocextractor.application.dataframeimport.model.ImportRecordSeparator;
import com.iocextractor.application.dataframeimport.model.ImportRejectedLogicalRow;
import com.iocextractor.application.dataframeimport.model.ImportRoutingPolicy;
import com.iocextractor.application.dataframeimport.model.ImportRowFailurePolicy;
import com.iocextractor.application.dataframeimport.model.ImportSha256;
import com.iocextractor.application.dataframeimport.model.ImportSnapshot;
import com.iocextractor.application.dataframeimport.model.ImportSnapshotReference;
import com.iocextractor.application.dataframeimport.model.ImportSourceId;
import com.iocextractor.application.dataframeimport.model.ImportSourceTransport;
import com.iocextractor.application.dataframeimport.model.ImportStage;
import com.iocextractor.application.dataframeimport.model.ImportStageReference;
import com.iocextractor.application.dataframeimport.model.ImportWorkspaceCapacity;
import com.iocextractor.application.dataframeimport.model.ImportWorkspaceLimits;
import com.iocextractor.application.port.out.dataframeimport.CreateImportWorkspaceCommand;
import com.iocextractor.application.port.out.dataframeimport.DelimitedHeaderReadCommand;
import com.iocextractor.application.port.out.dataframeimport.DelimitedReadCommand;
import com.iocextractor.application.port.out.dataframeimport.DelimitedRecordConsumer;
import com.iocextractor.application.port.out.dataframeimport.DelimitedRecordReader;
import com.iocextractor.application.port.out.dataframeimport.ImportWorkspace;
import com.iocextractor.application.port.out.dataframeimport.ImportWorkspaceWriter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DataframeImportStagingServiceTest {

    private static final ImportSourceId SOURCE = new ImportSourceId("local");
    private static final ImportSnapshot SNAPSHOT = new ImportSnapshot(
            new ImportSnapshotReference("snapshot:delivery"), new ImportSha256("a".repeat(64)), 128);

    @Test
    void streams_accepted_and_rejected_rows_then_verifies_the_sealed_stage() {
        CompiledDataframeImportContract contract = contract();
        FakeReader reader = new FakeReader();
        RecordingWorkspace workspace = new RecordingWorkspace();
        DataframeImportStagingService service = new DataframeImportStagingService(
                new DataframeImportRecognizer(catalog(contract), reader),
                new DataframeImportRowMapper((specification, value) -> value,
                        new CanonicalArtifactKeyResolver(List.of(
                                new ArtifactIdentityDefinition("ip_list", List.of("ip"), false, 1)))),
                reader, workspace, ImportWorkspaceLimits.defaults());

        ImportStagingResult result = service.stage(new ImportStagingCommand(
                new ImportDeliveryId("delivery"), SOURCE, SNAPSHOT));

        assertThat(result.contract().id()).isEqualTo(contract.id());
        assertThat(result.stage().sourceRows()).isEqualTo(2);
        assertThat(result.stage().acceptedRows()).isOne();
        assertThat(result.stage().rejectedRows()).isOne();
        assertThat(workspace.accepted).hasSize(1);
        assertThat(workspace.rejected).singleElement()
                .satisfies(row -> assertThat(row.issues()).extracting(issue -> issue.code())
                        .contains("IMPORT.FORMULA_REJECTED"));
        assertThat(workspace.verified).isTrue();
    }

    private DataframeImportCatalog catalog(CompiledDataframeImportContract contract) {
        return new DataframeImportCatalog(true,
                Map.of(SOURCE, new DataframeImportCatalogDraft.Source(
                        SOURCE.value(), ImportSourceTransport.LOCAL, "./var/import", null,
                        List.of(contract.id().value()), "standard")),
                Map.of(), Map.of(contract.id(), contract),
                new ImportCatalogFingerprint("b".repeat(64)));
    }

    private CompiledDataframeImportContract contract() {
        var definition = new DataframeImportCatalogDraft.Contract(
                "ip-list-v1", 1, "UTF-8",
                new DataframeImportCatalogDraft.Dialect(
                        ";", "\"", ImportRecordSeparator.CRLF_OR_LF, true, List.of("NULL")),
                new DataframeImportCatalogDraft.Recognition(
                        List.of("ip"), List.of("description"), List.of(), Map.of()),
                ImportProcessingMode.AS_IS, ImportRoutingPolicy.TARGET_ONLY,
                ImportRowFailurePolicy.ACCEPT_VALID, ImportDuplicatePolicy.COALESCE, true,
                ImportFormulaPolicy.REJECT, ImportMergePolicy.FILL_MISSING,
                List.of(new DataframeImportCatalogDraft.Artifact(
                        "ip_list", ImportArtifactRole.PRIMARY, "ip_list-row-v1", List.of(), null,
                        List.of(
                                new DataframeImportCatalogDraft.Column("ip", "ip", List.of(), null),
                                new DataframeImportCatalogDraft.Column(
                                        "description", "description", List.of(), null)))),
                null);
        return new CompiledDataframeImportContract(
                new ImportContractId("ip-list-v1"), 1, definition,
                new DelimitedDialect(';', '"', ImportRecordSeparator.CRLF_OR_LF, true, List.of("NULL")),
                new ImportContractFingerprint("c".repeat(64)));
    }

    private static final class FakeReader implements DelimitedRecordReader {
        @Override
        public List<String> readHeader(DelimitedHeaderReadCommand command) {
            return List.of("description", "ip");
        }

        @Override
        public void read(DelimitedReadCommand command, DelimitedRecordConsumer consumer) {
            consumer.accept(new com.iocextractor.application.dataframeimport.model.ImportDelimitedRecord(
                    2, Map.of("ip", "192.0.2.1", "description", "malicious")));
            consumer.accept(new com.iocextractor.application.dataframeimport.model.ImportDelimitedRecord(
                    3, Map.of("ip", "198.51.100.2", "description", "=formula")));
        }
    }

    private static final class RecordingWorkspace implements ImportWorkspace, ImportWorkspaceWriter {
        private final List<ImportLogicalRow> accepted = new ArrayList<>();
        private final List<ImportRejectedLogicalRow> rejected = new ArrayList<>();
        private CreateImportWorkspaceCommand command;
        private boolean verified;

        @Override
        public ImportWorkspaceWriter create(CreateImportWorkspaceCommand createCommand) {
            command = createCommand;
            return this;
        }

        @Override
        public ImportWorkspaceWriter rebuild(CreateImportWorkspaceCommand createCommand) {
            return create(createCommand);
        }

        @Override
        public java.util.Optional<ImportStage> adoptSealed(
                com.iocextractor.application.dataframeimport.model.ImportDeliveryId deliveryId,
                ImportSnapshot snapshot,
                com.iocextractor.application.dataframeimport.model.ImportContractPin contract) {
            return java.util.Optional.empty();
        }

        @Override
        public ImportStage verifySealed(CreateImportWorkspaceCommand createCommand, ImportStage expected) {
            assertThat(createCommand).isEqualTo(command);
            verified = true;
            return expected;
        }

        @Override
        public ImportWorkspaceCapacity capacity() {
            return new ImportWorkspaceCapacity(0, 1, ImportWorkspaceCapacity.State.ACCEPTING);
        }

        @Override
        public void discard(com.iocextractor.application.dataframeimport.model.ImportDeliveryId deliveryId) {
            // No external scratch state in this test double.
        }

        @Override
        public void append(ImportLogicalRow row) {
            accepted.add(row);
        }

        @Override
        public void reject(ImportRejectedLogicalRow row) {
            rejected.add(row);
        }

        @Override
        public ImportStage seal() {
            return new ImportStage(new ImportStageReference("stage:delivery"),
                    new ImportSha256("d".repeat(64)), accepted.size() + rejected.size(),
                    accepted.size(), rejected.size());
        }

        @Override
        public void close() {
        }
    }
}
