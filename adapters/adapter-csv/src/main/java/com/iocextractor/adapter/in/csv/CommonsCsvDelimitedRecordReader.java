package com.iocextractor.adapter.in.csv;

import com.iocextractor.application.dataframeimport.contract.DataframeImportCatalogDraft;
import com.iocextractor.application.dataframeimport.model.DelimitedDialect;
import com.iocextractor.application.dataframeimport.model.ImportDelimitedRecord;
import com.iocextractor.application.port.out.dataframeimport.DelimitedReadCommand;
import com.iocextractor.application.port.out.dataframeimport.DelimitedRecordConsumer;
import com.iocextractor.application.port.out.dataframeimport.DelimitedRecordReader;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.csv.DuplicateHeaderMode;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Commons CSV adapter with strict decoding, exact headers and callback streaming. */
public final class CommonsCsvDelimitedRecordReader implements DelimitedRecordReader {

    private final ImportSnapshotPathResolver snapshots;

    /** Creates a reader over adapter-owned immutable snapshot paths. */
    public CommonsCsvDelimitedRecordReader(ImportSnapshotPathResolver snapshots) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
    }

    @Override
    public void read(DelimitedReadCommand command, DelimitedRecordConsumer consumer) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(consumer, "consumer");
        Path path = Objects.requireNonNull(snapshots.resolve(command.snapshotReference()), "snapshot path");
        Charset charset = charset(command.charset());
        try (Reader decoded = strictReader(path, charset);
             Reader separators = new RecordSeparatorValidatingReader(decoded, command.dialect().recordSeparator());
             CSVParser parser = format(command.dialect()).parse(separators)) {
            HeaderPlan header = HeaderPlan.compile(parser.getHeaderNames(), command.recognition());
            for (CSVRecord record : parser) {
                if (!record.isConsistent()) {
                    throw new DelimitedRecordReadException(
                            "Delimited input row has a different column count than its header");
                }
                consumer.accept(new ImportDelimitedRecord(
                        Math.incrementExact(record.getRecordNumber()), header.values(record)));
            }
        } catch (DelimitedRecordReadException failure) {
            throw failure;
        } catch (CharacterCodingException failure) {
            throw new DelimitedRecordReadException(
                    "Delimited input contains malformed or unmappable bytes for the declared charset", failure);
        } catch (IOException failure) {
            throw new DelimitedRecordReadException("Cannot parse delimited input with the declared contract", failure);
        }
    }

    private Reader strictReader(Path path, Charset charset) throws IOException {
        return new InputStreamReader(Files.newInputStream(path), charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT));
    }

    private Charset charset(String name) {
        try {
            return Charset.forName(name);
        } catch (IllegalArgumentException failure) {
            throw new DelimitedRecordReadException("Delimited input charset is not supported", failure);
        }
    }

    private CSVFormat format(DelimitedDialect dialect) {
        return CSVFormat.Builder.create()
                .setDelimiter(dialect.delimiter())
                .setQuote(dialect.quote())
                .setHeader()
                .setSkipHeaderRecord(true)
                .setAllowMissingColumnNames(false)
                .setDuplicateHeaderMode(DuplicateHeaderMode.ALLOW_ALL)
                .setIgnoreEmptyLines(false)
                .setIgnoreSurroundingSpaces(false)
                .build();
    }

    private record HeaderPlan(List<String> canonicalByIndex, Set<String> ignored) {

        private HeaderPlan {
            canonicalByIndex = List.copyOf(canonicalByIndex);
            ignored = Set.copyOf(ignored);
        }

        static HeaderPlan compile(List<String> external,
                                  DataframeImportCatalogDraft.Recognition recognition) {
            Objects.requireNonNull(external, "external headers");
            Objects.requireNonNull(recognition, "recognition");
            Set<String> allowed = new LinkedHashSet<>();
            allowed.addAll(recognition.requiredColumns());
            allowed.addAll(recognition.optionalColumns());
            allowed.addAll(recognition.ignoredColumns());
            Set<String> ignored = Set.copyOf(recognition.ignoredColumns());
            Set<String> present = new HashSet<>();
            List<String> canonical = new ArrayList<>(external.size());
            int unexpected = 0;
            int duplicates = 0;
            for (String header : external) {
                String resolved = recognition.aliases().getOrDefault(header, header);
                canonical.add(resolved);
                if (!allowed.contains(resolved)) {
                    unexpected++;
                } else if (!present.add(resolved)) {
                    duplicates++;
                }
            }
            List<String> missing = recognition.requiredColumns().stream()
                    .filter(required -> !present.contains(required))
                    .toList();
            if (unexpected > 0 || duplicates > 0 || !missing.isEmpty()) {
                throw new DelimitedRecordReadException(
                        "Delimited input header does not match the configured signature"
                                + " (missing=" + missing.size()
                                + ", unexpected=" + unexpected
                                + ", duplicate=" + duplicates + ")");
            }
            return new HeaderPlan(canonical, ignored);
        }

        Map<String, String> values(CSVRecord record) {
            Map<String, String> values = new LinkedHashMap<>();
            for (int index = 0; index < canonicalByIndex.size(); index++) {
                String canonical = canonicalByIndex.get(index);
                if (!ignored.contains(canonical)) {
                    values.put(canonical, record.get(index));
                }
            }
            return values;
        }
    }
}
