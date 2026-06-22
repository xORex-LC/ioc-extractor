package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.application.port.out.IocSink;
import com.iocextractor.common.IocExtractorException;
import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.observability.EventAction;
import com.iocextractor.observability.EventOutcome;
import com.iocextractor.observability.LogField;
import com.iocextractor.observability.logging.LogEvents;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Set;

/**
 * CSV {@link IocSink} for a single artifact. Filters the indicators it accepts,
 * assigns ids and delegates row shaping to a {@link RowMapper}. The {@link CSVFormat}
 * is configured (delimiter {@code ;}, quote-all-non-null, NULL literal) so output
 * matches the reference dialect.
 */
public final class CsvIocSink implements IocSink {

    private static final Logger log = LoggerFactory.getLogger(CsvIocSink.class);

    private final String name;
    private final Path path;
    private final Set<IndicatorType> accepts;
    private final ArtifactFilter filter;
    private final RowMapper mapper;
    private final IdGenerator ids;
    private final CSVFormat format;
    private final Charset charset;

    public CsvIocSink(String name,
                      Path path,
                      Set<IndicatorType> accepts,
                      RowMapper mapper,
                      IdGenerator ids,
                      CSVFormat format) {
        this(name, path, accepts, ArtifactFilter.none(), mapper, ids, format, StandardCharsets.UTF_8);
    }

    public CsvIocSink(String name,
                      Path path,
                      Set<IndicatorType> accepts,
                      ArtifactFilter filter,
                      RowMapper mapper,
                      IdGenerator ids,
                      CSVFormat format) {
        this(name, path, accepts, filter, mapper, ids, format, StandardCharsets.UTF_8);
    }

    public CsvIocSink(String name,
                      Path path,
                      Set<IndicatorType> accepts,
                      ArtifactFilter filter,
                      RowMapper mapper,
                      IdGenerator ids,
                      CSVFormat format,
                      Charset charset) {
        this.name = name;
        this.path = path;
        this.accepts = Set.copyOf(accepts);
        this.filter = filter == null ? ArtifactFilter.none() : filter;
        this.mapper = mapper;
        this.ids = ids;
        this.format = format;
        this.charset = charset == null ? StandardCharsets.UTF_8 : charset;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public int write(List<Indicator> indicators) {
        List<Indicator> accepted = indicators.stream()
                .filter(i -> accepts.contains(i.type()))
                .filter(filter::accepts)
                .toList();
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Path temp = tempPath();
            try (BufferedWriter writer = CsvIo.newWriter(temp, charset);
                 CSVPrinter printer = new CSVPrinter(writer, format)) {
                printer.printRecord(mapper.header());
                for (Indicator indicator : accepted) {
                    printer.printRecord(mapper.toRow(ids.next(), indicator));
                }
            }
            moveIntoPlace(temp);
            LogEvents.info(log)
                    .action(EventAction.ARTIFACT_WRITE)
                    .outcome(EventOutcome.SUCCESS)
                    .field(LogField.IOC_ARTIFACT_NAME, name)
                    .field(LogField.FILE_PATH, path)
                    .field(LogField.IOC_ROWS, accepted.size())
                    .message("artifact written")
                    .log();
            return accepted.size();
        } catch (IOException e) {
            LogEvents.error(log)
                    .action(EventAction.ARTIFACT_WRITE)
                    .outcome(EventOutcome.FAILURE)
                    .field(LogField.IOC_ARTIFACT_NAME, name)
                    .field(LogField.FILE_PATH, path)
                    .field(LogField.IOC_ROWS, accepted.size())
                    .message("artifact write failed")
                    .log(e);
            throw new IocExtractorException("Failed to write artifact '" + name + "' to " + path, e);
        }
    }

    private Path tempPath() throws IOException {
        Path parent = path.getParent();
        if (parent == null) {
            parent = Path.of(".");
        }
        return Files.createTempFile(parent, path.getFileName().toString(), ".tmp");
    }

    private void moveIntoPlace(Path temp) throws IOException {
        try {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
