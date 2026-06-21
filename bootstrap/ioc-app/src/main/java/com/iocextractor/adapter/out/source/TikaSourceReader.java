package com.iocextractor.adapter.out.source;

import com.iocextractor.application.port.out.SourceReader;
import com.iocextractor.common.IocExtractorException;
import com.iocextractor.observability.EventAction;
import com.iocextractor.observability.EventOutcome;
import com.iocextractor.observability.LogField;
import com.iocextractor.observability.logging.LogEvents;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Format-agnostic {@link SourceReader} backed by Apache Tika. Auto-detects the
 * document type (.docx / .htm / .pdf / .xlsx …) and returns its plain text.
 * The {@code -1} content limit disables Tika's default 100k-char truncation.
 */
public final class TikaSourceReader implements SourceReader {

    private static final Logger log = LoggerFactory.getLogger(TikaSourceReader.class);

    private final Parser parser = new AutoDetectParser();

    @Override
    public String readText(Path source) {
        try (InputStream in = Files.newInputStream(source)) {
            BodyContentHandler handler = new BodyContentHandler(-1);
            Metadata metadata = new Metadata();
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, source.getFileName().toString());
            parser.parse(in, handler, metadata, new ParseContext());
            var text = handler.toString();
            LogEvents.info(log)
                    .action(EventAction.SOURCE_READ)
                    .outcome(EventOutcome.SUCCESS)
                    .field(LogField.IOC_SOURCE_PATH, source)
                    .message("source read")
                    .log();
            return text;
        } catch (Exception e) {
            LogEvents.error(log)
                    .action(EventAction.SOURCE_READ)
                    .outcome(EventOutcome.FAILURE)
                    .field(LogField.IOC_SOURCE_PATH, source)
                    .message("source read failed")
                    .log(e);
            throw new IocExtractorException("Failed to read source: " + source, e);
        }
    }
}
