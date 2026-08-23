package com.iocextractor.application.dataframeimport.contract;

import com.iocextractor.application.dataframeimport.model.DelimitedDialect;
import com.iocextractor.application.dataframeimport.model.DelimitedInputLimits;
import com.iocextractor.application.dataframeimport.model.ImportArtifactRole;
import com.iocextractor.application.dataframeimport.model.ImportCatalogFingerprint;
import com.iocextractor.application.dataframeimport.model.ImportContractFingerprint;
import com.iocextractor.application.dataframeimport.model.ImportContractId;
import com.iocextractor.application.dataframeimport.model.ImportDuplicatePolicy;
import com.iocextractor.application.dataframeimport.model.ImportFormulaPolicy;
import com.iocextractor.application.dataframeimport.model.ImportMergePolicy;
import com.iocextractor.application.dataframeimport.model.ImportProcessingMode;
import com.iocextractor.application.dataframeimport.model.ImportRecordSeparator;
import com.iocextractor.application.dataframeimport.model.ImportRoutingPolicy;
import com.iocextractor.application.dataframeimport.model.ImportRowFailurePolicy;
import com.iocextractor.application.dataframeimport.model.ImportSnapshotReference;
import com.iocextractor.application.dataframeimport.model.ImportSourceId;
import com.iocextractor.application.dataframeimport.model.ImportSourceTransport;
import com.iocextractor.application.port.out.dataframeimport.DelimitedHeaderReadCommand;
import com.iocextractor.application.port.out.dataframeimport.DelimitedReadCommand;
import com.iocextractor.application.port.out.dataframeimport.DelimitedRecordConsumer;
import com.iocextractor.application.port.out.dataframeimport.DelimitedRecordReader;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataframeImportRecognizerTest {

    private static final ImportSourceId SOURCE = new ImportSourceId("local");
    private static final ImportSnapshotReference SNAPSHOT = new ImportSnapshotReference("snapshot:one");

    @Test
    void selects_exactly_one_allowlisted_contract_without_using_catalog_order() {
        CompiledDataframeImportContract ip = contract("ip-v1", List.of("ip"));
        CompiledDataframeImportContract hashes = contract("hash-v1", List.of("hash"));
        DataframeImportRecognizer recognizer = new DataframeImportRecognizer(
                catalog(List.of("hash-v1", "ip-v1"), hashes, ip), reader(List.of("ip")));

        assertThat(recognizer.recognize(SOURCE, SNAPSHOT, DelimitedInputLimits.defaults()).id())
                .isEqualTo(new ImportContractId("ip-v1"));
    }

    @Test
    void rejects_zero_and_multiple_matches_as_distinct_critical_reasons() {
        CompiledDataframeImportContract first = contract("first", List.of("ip"));
        CompiledDataframeImportContract second = contract("second", List.of("ip"));

        assertThatThrownBy(() -> new DataframeImportRecognizer(
                catalog(List.of("first"), first), reader(List.of("hash")))
                .recognize(SOURCE, SNAPSHOT, DelimitedInputLimits.defaults()))
                .isInstanceOfSatisfying(ImportRecognitionException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                ImportRecognitionException.Reason.CONTRACT_NOT_RECOGNIZED));

        assertThatThrownBy(() -> new DataframeImportRecognizer(
                catalog(List.of("first", "second"), first, second), reader(List.of("ip")))
                .recognize(SOURCE, SNAPSHOT, DelimitedInputLimits.defaults()))
                .isInstanceOfSatisfying(ImportRecognitionException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                ImportRecognitionException.Reason.CONTRACT_AMBIGUOUS));
    }

    @Test
    void source_allowlist_prevents_a_structurally_matching_untrusted_contract() {
        CompiledDataframeImportContract allowed = contract("allowed", List.of("hash"));
        CompiledDataframeImportContract notAllowed = contract("not-allowed", List.of("ip"));
        DataframeImportRecognizer recognizer = new DataframeImportRecognizer(
                catalog(List.of("allowed"), allowed, notAllowed), reader(List.of("ip")));

        assertThatThrownBy(() -> recognizer.recognize(
                SOURCE, SNAPSHOT, DelimitedInputLimits.defaults()))
                .isInstanceOfSatisfying(ImportRecognitionException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                ImportRecognitionException.Reason.CONTRACT_NOT_RECOGNIZED));
    }

    private DataframeImportCatalog catalog(List<String> allowlist,
                                           CompiledDataframeImportContract... contracts) {
        Map<ImportContractId, CompiledDataframeImportContract> indexed = new LinkedHashMap<>();
        for (CompiledDataframeImportContract contract : contracts) {
            indexed.put(contract.id(), contract);
        }
        return new DataframeImportCatalog(true,
                Map.of(SOURCE, new DataframeImportCatalogDraft.Source(
                        SOURCE.value(), ImportSourceTransport.LOCAL, "./var/import", null,
                        allowlist, "standard")),
                Map.of(), indexed, new ImportCatalogFingerprint("a".repeat(64)));
    }

    private CompiledDataframeImportContract contract(String id, List<String> headers) {
        DataframeImportCatalogDraft.Contract definition = new DataframeImportCatalogDraft.Contract(
                id, 1, "UTF-8",
                new DataframeImportCatalogDraft.Dialect(
                        ";", "\"", ImportRecordSeparator.CRLF_OR_LF, true, List.of("NULL")),
                new DataframeImportCatalogDraft.Recognition(headers, List.of(), List.of(), Map.of()),
                ImportProcessingMode.AS_IS, ImportRoutingPolicy.TARGET_ONLY,
                ImportRowFailurePolicy.ACCEPT_VALID, ImportDuplicatePolicy.COALESCE, true,
                ImportFormulaPolicy.REJECT, ImportMergePolicy.FILL_MISSING,
                List.of(new DataframeImportCatalogDraft.Artifact(
                        "ip_list", ImportArtifactRole.PRIMARY, "ip-row-v1", List.of(), null,
                        List.of())), null);
        return new CompiledDataframeImportContract(
                new ImportContractId(id), 1, definition,
                new DelimitedDialect(';', '"', ImportRecordSeparator.CRLF_OR_LF, true, List.of("NULL")),
                new ImportContractFingerprint(Integer.toHexString(id.hashCode()).repeat(64).substring(0, 64)));
    }

    private DelimitedRecordReader reader(List<String> header) {
        return new DelimitedRecordReader() {
            @Override
            public List<String> readHeader(DelimitedHeaderReadCommand command) {
                return header;
            }

            @Override
            public void read(DelimitedReadCommand command, DelimitedRecordConsumer consumer) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
