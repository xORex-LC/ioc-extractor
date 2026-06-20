package com.iocextractor.adapter.out.source;

import com.iocextractor.application.port.out.SourceReader;
import com.iocextractor.common.IocExtractorException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Format-agnostic {@link SourceReader} backed by Apache Tika. Auto-detects the
 * document type (.docx / .htm / .pdf / .xlsx …) and returns its plain text.
 * The {@code -1} content limit disables Tika's default 100k-char truncation.
 */
public final class TikaSourceReader implements SourceReader {

    private final Parser parser = new AutoDetectParser();

    @Override
    public String readText(Path source) {
        try (InputStream in = Files.newInputStream(source)) {
            BodyContentHandler handler = new BodyContentHandler(-1);
            Metadata metadata = new Metadata();
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, source.getFileName().toString());
            parser.parse(in, handler, metadata, new ParseContext());
            return handler.toString();
        } catch (Exception e) {
            throw new IocExtractorException("Failed to read source: " + source, e);
        }
    }
}
