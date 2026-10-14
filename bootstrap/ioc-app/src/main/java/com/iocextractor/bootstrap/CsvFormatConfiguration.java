package com.iocextractor.bootstrap;

import com.iocextractor.application.export.ExportFormat;

import java.nio.charset.Charset;

/** Resolves one CSV policy for preflight, mutable projections and immutable exports. */
final class CsvFormatConfiguration {

    private CsvFormatConfiguration() {
    }

    static ExportFormat resolve(IocProperties.Sink.Csv csv) {
        String charset = csv.charset() == null || csv.charset().isBlank() ? "UTF-8" : csv.charset().trim();
        try {
            Charset.forName(charset);
        } catch (IllegalArgumentException invalidCharset) {
            throw new IllegalArgumentException("Unsupported export charset: " + charset, invalidCharset);
        }
        if (csv.delimiter().length() != 1 || csv.quote().length() != 1) {
            throw new IllegalArgumentException("Export CSV delimiter and quote must be one character");
        }
        char delimiter = csv.delimiter().charAt(0);
        char quote = csv.quote().charAt(0);
        if (delimiter == quote || delimiter == '\r' || delimiter == '\n'
                || quote == '\r' || quote == '\n') {
            throw new IllegalArgumentException("Export CSV delimiter and quote must be distinct non-line-breaks");
        }
        String nullLiteral = csv.nullLiteral();
        if (nullLiteral.contains(csv.delimiter()) || nullLiteral.contains(csv.quote())
                || nullLiteral.indexOf('\r') >= 0 || nullLiteral.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(
                    "ioc.sink.csv.null-literal must not contain delimiter, quote or line breaks");
        }
        return new ExportFormat("csv", charset, csv.delimiter(), csv.quote(), nullLiteral,
                csv.recordSeparator(), csv.headerQuoting(), csv.valueQuoting());
    }
}
