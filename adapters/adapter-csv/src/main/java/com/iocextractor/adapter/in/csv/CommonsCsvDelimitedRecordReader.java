package com.iocextractor.adapter.in.csv;

import com.iocextractor.application.dataframeimport.mapping.ImportHeaderPlan;
import com.iocextractor.application.dataframeimport.model.DelimitedDialect;
import com.iocextractor.application.dataframeimport.model.DelimitedInputLimits;
import com.iocextractor.application.dataframeimport.model.ImportDelimitedRecord;
import com.iocextractor.application.port.out.dataframeimport.DelimitedHeaderReadCommand;
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
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Commons CSV adapter with strict decoding, exact headers and callback streaming. */
public final class CommonsCsvDelimitedRecordReader implements DelimitedRecordReader {

    private final ImportSnapshotPathResolver snapshots;

    /** Creates a reader over adapter-owned immutable snapshot paths. */
    public CommonsCsvDelimitedRecordReader(ImportSnapshotPathResolver snapshots) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
    }

    @Override
    public List<String> readHeader(DelimitedHeaderReadCommand command) {
        Objects.requireNonNull(command, "command");
        Path path = Objects.requireNonNull(snapshots.resolve(command.snapshotReference()), "snapshot path");
        Charset charset = charset(command.charset());
        try (Reader decoded = strictReader(path, charset);
             Reader separators = new RecordSeparatorValidatingReader(decoded, command.dialect().recordSeparator());
             Reader limited = new DelimitedInputLimitingReader(separators, command.dialect(), command.limits());
             CSVParser parser = format(command.dialect()).parse(limited)) {
            List<String> headers = List.copyOf(parser.getHeaderNames());
            requireHeaderLimit(headers, command.limits());
            return headers;
        } catch (DelimitedRecordReadException failure) {
            throw failure;
        } catch (UncheckedIOException failure) {
            throw readFailure(failure, "Cannot parse delimited input header");
        } catch (CharacterCodingException failure) {
            throw readFailure(failure, "Cannot parse delimited input header");
        } catch (IOException failure) {
            throw readFailure(failure, "Cannot parse delimited input header");
        }
    }

    @Override
    public void read(DelimitedReadCommand command, DelimitedRecordConsumer consumer) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(consumer, "consumer");
        Path path = Objects.requireNonNull(snapshots.resolve(command.snapshotReference()), "snapshot path");
        Charset charset = charset(command.charset());
        try (Reader decoded = strictReader(path, charset);
             Reader separators = new RecordSeparatorValidatingReader(decoded, command.dialect().recordSeparator());
             Reader limited = new DelimitedInputLimitingReader(separators, command.dialect(), command.limits());
             CSVParser parser = format(command.dialect()).parse(limited)) {
            requireHeaderLimit(parser.getHeaderNames(), command.limits());
            ImportHeaderPlan header = headerPlan(parser.getHeaderNames(), command);
            for (CSVRecord record : parser) {
                requireRecordLimits(record, command.limits());
                consumer.accept(new ImportDelimitedRecord(
                        Math.incrementExact(record.getRecordNumber()),
                        header.values(record.size(), record::get)));
            }
        } catch (DelimitedRecordReadException failure) {
            throw failure;
        } catch (UncheckedIOException failure) {
            throw readFailure(failure, "Cannot parse delimited input with the declared contract");
        } catch (CharacterCodingException failure) {
            throw readFailure(failure, "Cannot parse delimited input with the declared contract");
        } catch (IOException failure) {
            throw readFailure(failure, "Cannot parse delimited input with the declared contract");
        }
    }

    private DelimitedRecordReadException readFailure(UncheckedIOException failure, String fallbackMessage) {
        return new DelimitedRecordReadException(failureMessage(failure.getCause(), fallbackMessage), failure);
    }

    private DelimitedRecordReadException readFailure(IOException failure, String fallbackMessage) {
        return new DelimitedRecordReadException(failureMessage(failure, fallbackMessage), failure);
    }

    private String failureMessage(IOException failure, String fallbackMessage) {
        if (failure instanceof CharacterCodingException) {
            return "Delimited input contains malformed or unmappable bytes for the declared charset";
        }
        if (failure instanceof DelimitedInputLimitingReader.InputLimitException) {
            return failure.getMessage();
        }
        return fallbackMessage;
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

    private ImportHeaderPlan headerPlan(List<String> headers, DelimitedReadCommand command) {
        try {
            return ImportHeaderPlan.compile(headers, command.recognition());
        } catch (IllegalArgumentException failure) {
            throw new DelimitedRecordReadException(failure.getMessage(), failure);
        }
    }

    private void requireHeaderLimit(List<String> headers, DelimitedInputLimits limits) {
        if (headers.size() > limits.maximumColumns()) {
            throw new DelimitedRecordReadException("Delimited input exceeds the configured column limit");
        }
        long headerCharacters = 0;
        for (String header : headers) {
            if (header.length() > limits.maximumFieldCharacters()) {
                throw new DelimitedRecordReadException("Delimited input exceeds the configured field limit");
            }
            headerCharacters += header.length();
            if (headerCharacters > limits.maximumRecordCharacters()) {
                throw new DelimitedRecordReadException("Delimited input exceeds the configured record limit");
            }
        }
    }

    private void requireRecordLimits(CSVRecord record, DelimitedInputLimits limits) {
        if (!record.isConsistent()) {
            throw new DelimitedRecordReadException(
                    "Delimited input row has a different column count than its header");
        }
        if (record.getRecordNumber() > limits.maximumRows()) {
            throw new DelimitedRecordReadException("Delimited input exceeds the configured row limit");
        }
        long recordCharacters = 0;
        for (String value : record) {
            if (value.length() > limits.maximumFieldCharacters()) {
                throw new DelimitedRecordReadException("Delimited input exceeds the configured field limit");
            }
            recordCharacters += value.length();
            if (recordCharacters > limits.maximumRecordCharacters()) {
                throw new DelimitedRecordReadException("Delimited input exceeds the configured record limit");
            }
        }
    }
}
