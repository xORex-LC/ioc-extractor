package com.iocextractor.application.dataframeimport;

import com.iocextractor.application.dataframeimport.contract.CompiledDataframeImportContract;
import com.iocextractor.application.dataframeimport.contract.DataframeImportRecognizer;
import com.iocextractor.application.dataframeimport.contract.ImportRecognitionException;
import com.iocextractor.application.dataframeimport.mapping.DataframeImportRowMapper;
import com.iocextractor.application.dataframeimport.model.ImportWorkspaceLimits;
import com.iocextractor.application.port.in.dataframeimport.ValidateDataframeImportCommand;
import com.iocextractor.application.port.in.dataframeimport.ValidateDataframeImportResult;
import com.iocextractor.application.port.in.dataframeimport.ValidateDataframeImportUseCase;
import com.iocextractor.application.port.out.dataframeimport.DelimitedReadCommand;
import com.iocextractor.application.port.out.dataframeimport.DelimitedRecordReader;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Side-effect-free structural and row-mapping preview over caller-owned bytes. */
public final class DataframeImportValidationService implements ValidateDataframeImportUseCase {

    private final DataframeImportRecognizer recognizer;
    private final DataframeImportRowMapper mapper;
    private final DelimitedRecordReader reader;
    private final ImportWorkspaceLimits limits;

    /** Creates an advisory validator with the same catalog and parser limits as runtime staging. */
    public DataframeImportValidationService(DataframeImportRecognizer recognizer,
                                            DataframeImportRowMapper mapper,
                                            DelimitedRecordReader reader,
                                            ImportWorkspaceLimits limits) {
        this.recognizer = Objects.requireNonNull(recognizer, "recognizer");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.reader = Objects.requireNonNull(reader, "reader");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    @Override
    public ValidateDataframeImportResult validate(ValidateDataframeImportCommand command) {
        Objects.requireNonNull(command, "command");
        try {
            CompiledDataframeImportContract contract = recognizer.recognize(
                    command.sourceId(), command.snapshotReference(), limits.inputLimits());
            long[] counts = new long[3];
            List<String> codes = new ArrayList<>();
            reader.read(new DelimitedReadCommand(
                            command.snapshotReference(), contract.definition().charset(), contract.dialect(),
                            contract.definition().recognition(), limits.inputLimits()),
                    record -> collect(contract, record, counts, codes));
            return new ValidateDataframeImportResult(
                    counts[2] == 0, Optional.of(contract.fingerprint()),
                    counts[0], counts[1], counts[2], codes);
        } catch (ImportRecognitionException failure) {
            return new ValidateDataframeImportResult(false, Optional.empty(), 0, 0, 0,
                    List.of("IMPORT." + failure.reason().name()));
        } catch (RuntimeException failure) {
            return new ValidateDataframeImportResult(false, Optional.empty(), 0, 0, 0,
                    List.of("IMPORT.INPUT_INVALID"));
        }
    }

    private void collect(CompiledDataframeImportContract contract,
                         com.iocextractor.application.dataframeimport.model.ImportDelimitedRecord record,
                         long[] counts,
                         List<String> codes) {
        counts[0]++;
        var mapped = mapper.map(contract, record);
        if (mapped.row().isPresent()) {
            counts[1]++;
            return;
        }
        counts[2]++;
        long remaining = Math.max(0L, limits.maximumRowErrors() - codes.size());
        mapped.issues().stream().map(issue -> issue.code()).limit(remaining).forEach(codes::add);
    }
}
