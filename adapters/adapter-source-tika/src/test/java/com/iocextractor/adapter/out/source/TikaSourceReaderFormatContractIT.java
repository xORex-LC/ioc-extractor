package com.iocextractor.adapter.out.source;

import com.iocextractor.application.tck.junit.ContractTest;
import com.iocextractor.application.tck.junit.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
@ContractTest
class TikaSourceReaderFormatContractIT {

    private static final String PDF_SENTINEL = "TIKA_PDF_SENTINEL";
    private static final String DOCX_SENTINEL = "TIKA_DOCX_SENTINEL";
    private static final String XLSX_SENTINEL = "TIKA_XLSX_SENTINEL";

    @TempDir
    Path tempDir;

    @Test
    void reads_text_from_pdf() throws Exception {
        Path source = tempDir.resolve("source.pdf");
        Files.write(source, minimalPdf(PDF_SENTINEL));

        String text = new TikaSourceReader().readText(source);

        assertThat(text).contains(PDF_SENTINEL);
    }

    @Test
    void reads_text_from_docx() throws Exception {
        Path source = tempDir.resolve("source.docx");
        writeZip(source, docxEntries(DOCX_SENTINEL));

        String text = new TikaSourceReader().readText(source);

        assertThat(text).contains(DOCX_SENTINEL);
    }

    @Test
    void reads_text_from_xlsx() throws Exception {
        Path source = tempDir.resolve("source.xlsx");
        writeZip(source, xlsxEntries(XLSX_SENTINEL));

        String text = new TikaSourceReader().readText(source);

        assertThat(text).contains(XLSX_SENTINEL);
    }

    private static byte[] minimalPdf(String text) throws IOException {
        String content = "BT /F1 12 Tf 72 720 Td (" + text + ") Tj ET";
        List<String> objects = List.of(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
                        + "/Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
                "<< /Length " + content.getBytes(StandardCharsets.US_ASCII).length + " >>\n"
                        + "stream\n" + content + "\nendstream",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
        );

        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        writeAscii(pdf, "%PDF-1.4\n");
        int[] offsets = new int[objects.size() + 1];
        for (int index = 0; index < objects.size(); index++) {
            int objectNumber = index + 1;
            offsets[objectNumber] = pdf.size();
            writeAscii(pdf, objectNumber + " 0 obj\n" + objects.get(index) + "\nendobj\n");
        }

        int xrefOffset = pdf.size();
        writeAscii(pdf, "xref\n0 " + offsets.length + "\n");
        writeAscii(pdf, "0000000000 65535 f \n");
        for (int objectNumber = 1; objectNumber < offsets.length; objectNumber++) {
            writeAscii(pdf, String.format(Locale.ROOT, "%010d 00000 n \n", offsets[objectNumber]));
        }
        writeAscii(pdf, "trailer\n<< /Size " + offsets.length + " /Root 1 0 R >>\n"
                + "startxref\n" + xrefOffset + "\n%%EOF\n");
        return pdf.toByteArray();
    }

    private static Map<String, String> docxEntries(String text) {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("[Content_Types].xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                  <Default Extension="xml" ContentType="application/xml"/>
                  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                </Types>
                """);
        entries.put("_rels/.rels", """
                <?xml version="1.0" encoding="UTF-8"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                </Relationships>
                """);
        entries.put("word/document.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body><w:p><w:r><w:t>%s</w:t></w:r></w:p></w:body>
                </w:document>
                """.formatted(text));
        return entries;
    }

    private static Map<String, String> xlsxEntries(String text) {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("[Content_Types].xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                  <Default Extension="xml" ContentType="application/xml"/>
                  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                </Types>
                """);
        entries.put("_rels/.rels", """
                <?xml version="1.0" encoding="UTF-8"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                </Relationships>
                """);
        entries.put("xl/workbook.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                          xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <sheets><sheet name="Indicators" sheetId="1" r:id="rId1"/></sheets>
                </workbook>
                """);
        entries.put("xl/_rels/workbook.xml.rels", """
                <?xml version="1.0" encoding="UTF-8"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                </Relationships>
                """);
        entries.put("xl/worksheets/sheet1.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>%s</t></is></c></row></sheetData>
                </worksheet>
                """.formatted(text));
        return entries;
    }

    private static void writeZip(Path destination, Map<String, String> entries) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(destination))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
    }

    private static void writeAscii(ByteArrayOutputStream output, String value) throws IOException {
        output.write(value.getBytes(StandardCharsets.US_ASCII));
    }
}
