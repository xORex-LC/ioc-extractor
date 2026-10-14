package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.application.export.ExportFormat;
import com.iocextractor.application.export.ExportQuoting;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.QuoteMode;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** Shared CSV byte policy for mutable projections and immutable slices; owns no streams. */
final class CsvRecordFormat {

    private final ExportFormat policy;
    private final CSVFormat quoted;
    private final CSVFormat minimal;

    CsvRecordFormat(ExportFormat policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
        if (!"csv".equalsIgnoreCase(policy.type())) {
            throw new IllegalArgumentException("CSV writer requires csv export format");
        }
        if (policy.delimiter().length() != 1 || policy.quote().length() != 1) {
            throw new IllegalArgumentException("CSV delimiter and quote must each be one character");
        }
        quoted = CSVFormat.Builder.create()
                .setDelimiter(policy.delimiter().charAt(0))
                .setQuote(policy.quote().charAt(0))
                .setNullString(policy.nullLiteral())
                .setQuoteMode(QuoteMode.ALL_NON_NULL)
                .setRecordSeparator(policy.recordSeparator().characters())
                .build();
        minimal = quoted.builder().setQuoteMode(QuoteMode.MINIMAL).build();
        if (containsSyntax(policy.nullLiteral())) {
            throw new IllegalArgumentException("CSV null marker must not contain delimiter, quote or line breaks");
        }
    }

    void printHeader(Appendable output, List<String> values) throws IOException {
        print(output, values, policy.headerQuoting(), null);
    }

    void printRecord(Appendable output, List<String> values) throws IOException {
        print(output, values, policy.valueQuoting(), policy.nullLiteral());
    }

    private void print(Appendable output, List<String> values, ExportQuoting quoting,
                       String reservedNullLiteral) throws IOException {
        if (quoting == ExportQuoting.NONE) {
            printUnquoted(output, values, reservedNullLiteral);
            return;
        }
        for (int index = 0; index < values.size(); index++) {
            String value = values.get(index);
            // MINIMAL alone does not distinguish the literal text "NULL" from a missing value.
            CSVFormat format = quoting == ExportQuoting.ALL
                    || (value != null && value.equals(reservedNullLiteral)) ? quoted : minimal;
            format.print(value, output, index == 0);
        }
        quoted.println(output);
    }

    private void printUnquoted(Appendable output, List<String> values,
                               String reservedNullLiteral) throws IOException {
        for (int index = 0; index < values.size(); index++) {
            String value = values.get(index);
            if (value != null && (containsSyntax(value) || value.equals(reservedNullLiteral)
                    || (values.size() == 1 && value.isEmpty()))) {
                throw new IOException("CSV NONE cannot represent column " + (index + 1)
                        + " without quoting; use ALL or MINIMAL");
            }
        }
        for (int index = 0; index < values.size(); index++) {
            if (index != 0) {
                output.append(policy.delimiter());
            }
            String value = values.get(index);
            output.append(value == null ? policy.nullLiteral() : value);
        }
        output.append(policy.recordSeparator().characters());
    }

    private boolean containsSyntax(String value) {
        return value.contains(policy.delimiter()) || value.contains(policy.quote())
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
    }
}
