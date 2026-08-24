package com.iocextractor.application.dataframeimport;

import com.iocextractor.application.dataframeimport.contract.CompiledDataframeImportContract;
import com.iocextractor.application.dataframeimport.contract.DataframeImportRecognizer;
import com.iocextractor.application.dataframeimport.mapping.DataframeImportRowMapper;
import com.iocextractor.application.dataframeimport.mapping.ImportRowMappingResult;
import com.iocextractor.application.dataframeimport.model.ImportContractPin;
import com.iocextractor.application.dataframeimport.model.ImportRejectedLogicalRow;
import com.iocextractor.application.dataframeimport.model.ImportPromotionPolicy;
import com.iocextractor.application.dataframeimport.model.ImportRequestedSlotPolicy;
import com.iocextractor.application.dataframeimport.model.ImportStage;
import com.iocextractor.application.dataframeimport.model.ImportWorkspaceLimits;
import com.iocextractor.application.port.out.dataframeimport.CreateImportWorkspaceCommand;
import com.iocextractor.application.port.out.dataframeimport.DelimitedReadCommand;
import com.iocextractor.application.port.out.dataframeimport.DelimitedRecordReader;
import com.iocextractor.application.port.out.dataframeimport.ImportWorkspace;
import com.iocextractor.application.port.out.dataframeimport.ImportWorkspaceWriter;

import java.util.Objects;
import java.util.Optional;

/**
 * Recognition-to-sealed-stage application service. It performs no canonical
 * database write and leaves recovery state transitions to the P5/P6 orchestrator.
 */
public final class DataframeImportStagingService {

    private final DataframeImportRecognizer recognizer;
    private final DataframeImportRowMapper mapper;
    private final DelimitedRecordReader reader;
    private final ImportWorkspace workspace;
    private final ImportWorkspaceLimits limits;

    /** Creates the framework-free staging orchestration boundary. */
    public DataframeImportStagingService(DataframeImportRecognizer recognizer,
                                         DataframeImportRowMapper mapper,
                                         DelimitedRecordReader reader,
                                         ImportWorkspace workspace,
                                         ImportWorkspaceLimits limits) {
        this.recognizer = Objects.requireNonNull(recognizer, "recognizer");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.reader = Objects.requireNonNull(reader, "reader");
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /** Strictly recognizes, maps and disk-stages one immutable delivery snapshot. */
    public ImportStagingResult stage(ImportStagingCommand command) {
        Objects.requireNonNull(command, "command");
        CompiledDataframeImportContract contract = recognizer.recognize(
                command.sourceId(), command.snapshot().reference(), limits.inputLimits());
        ImportContractPin pin = new ImportContractPin(
                contract.id(), contract.version(), contract.fingerprint());
        CreateImportWorkspaceCommand workspaceCommand = new CreateImportWorkspaceCommand(
                command.deliveryId(), command.snapshot(), pin, contract.definition().duplicatePolicy(),
                promotionPolicy(contract));
        try (ImportWorkspaceWriter writer = workspace.rebuild(workspaceCommand)) {
            reader.read(new DelimitedReadCommand(
                            command.snapshot().reference(), contract.definition().charset(), contract.dialect(),
                            contract.definition().recognition(), limits.inputLimits()),
                    record -> append(writer, mapper.map(contract, record)));
            ImportStage stage = writer.seal();
            workspace.verifySealed(workspaceCommand, stage);
            return new ImportStagingResult(pin, stage);
        }
    }

    private ImportPromotionPolicy promotionPolicy(CompiledDataframeImportContract contract) {
        var requested = contract.definition().requestedSlot();
        Optional<ImportRequestedSlotPolicy> slotPolicy = requested == null
                ? Optional.empty()
                : Optional.of(new ImportRequestedSlotPolicy(
                        requested.profile(), requested.existingRecordPolicy()));
        return new ImportPromotionPolicy(
                contract.definition().rowFailurePolicy(),
                contract.definition().renewUnchanged(),
                slotPolicy);
    }

    private void append(ImportWorkspaceWriter writer, ImportRowMappingResult result) {
        result.row().ifPresentOrElse(writer::append,
                () -> writer.reject(new ImportRejectedLogicalRow(
                        result.issues().getFirst().sourceRowNumber(), result.issues())));
    }
}
