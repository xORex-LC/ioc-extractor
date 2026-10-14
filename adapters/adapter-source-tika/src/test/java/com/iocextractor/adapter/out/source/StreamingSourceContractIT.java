package com.iocextractor.adapter.out.source;

import com.iocextractor.application.tck.junit.IntegrationTest;
import com.iocextractor.diagnostics.DiagnosticFactory;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Compares complete text, including whitespace/UTF-16 offsets, against the previous parser. */
@IntegrationTest
@Timeout(20)
class StreamingSourceContractIT {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {
            "<head><title>hidden</title><script>hidden</script></head><body><h2>БИБ-1</h2><p>😀 hxxps[:]//example[.]org/api?q=1</p><p>10.0.0.1<br>MD5 &amp; NBSP&nbsp;x</p></body>",
            "<body>before<div><p>a<span> b</span> c</p><p>d</p>e</div>after</body>",
            "<body><table>fostered<tr><td>A</td><td>B</td></tr><tr><td>C</td><td>D</td></tr></table>tail</body>",
            "<body><b>one<i>two</b>three</i><p>four</p><p>five</body>",
            "<body><img src='x' alt='example.org'><hr><pre>x\n y</pre><textarea>escaped &lt;x&gt;</textarea></body>",
            "<body><ul><li>one<li>two</ul><select><option>a<option>b</select><p>c</body>",
            "<body><div><div>unclosed<span>inline</body>",
            "<body><p>first</p><script>hidden</script><style>hidden</style><p>last</p></body>",
            "<body><div/>one<p>two</p>three<custom/>four</body>",
            "<frameset><frame src='one.html'><noframes>hidden</noframes></frameset>",
            "<body><!-- ignored --><noscript><div>hidden<p>nested</p></div></noscript><p>visible.test</p></body>",
            "<body><img src='x'><a href='https://example.org' onclick='ignored()' data-custom='ignored'>visible.test</a></body>",
            "<body><table><!-- retained repair-region comment --><tr><td>visible.test</td></tr></table></body>",
            "<body><svg><style><g>hidden.test</g></style><text>visible.test</text></svg></body>"
    })
    void htmlTextAndOffsetsAreIdenticalToThePreviousParser(String html) throws Exception {
        Path source = directory.resolve("source.html");
        Files.writeString(source, "<!doctype html><html>" + html + "</html>");
        assertThat(stream(source, 262144)).isEqualTo(previous(source));
    }

    @Test
    void missingEncodingDetectionUsesTheAsciiFallbackWithoutDroppingText() throws Exception {
        var output = new StringWriter();
        var context = new ParseContext();
        context.set(org.apache.tika.detect.EncodingDetector.class, (input, metadata) -> null);
        try (var input = new java.io.ByteArrayInputStream("<html><body><p>ascii.test</p></body></html>"
                .getBytes(StandardCharsets.US_ASCII))) {
            new StreamingHtmlParser(32768).parse(input, new BodyContentHandler(output), new Metadata(), context);
        }
        assertThat(output.toString()).isEqualTo("ascii.test\n");
    }

    @ParameterizedTest
    @ValueSource(strings = {"<html><body>", "<!-- comment only -->", "<body><div><p>last.test",
            "<table><tr><td>last.test"})
    void eofClosesUnterminatedAncestorsWithoutLosingOrDuplicatingText(String html) throws Exception {
        Path source = directory.resolve("eof.html");
        Files.writeString(source, html);
        assertThat(stream(source, 32768)).isEqualTo(previous(source));
    }

    @Test
    void cancelledRegionReaderPreservesInterruptionAndReleasesItsInput() throws Exception {
        var input = new java.io.StringReader("unread");
        try (var reader = new StreamingHtmlParser.RegionReader(input, 4)) {
            Thread.currentThread().interrupt();
            try {
                assertThatThrownBy(() -> reader.read(new char[1], 0, 1))
                        .isInstanceOf(java.io.IOException.class).hasMessage("HTML parsing interrupted");
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally { Thread.interrupted(); }
        }
        assertThatThrownBy(input::read).hasMessage("Stream closed");
    }

    @Test
    void manyDisposableParagraphsDoNotExhaustTheRegionBudget() throws Exception {
        Path source = directory.resolve("many.html");
        Files.writeString(source, "<html><body>" + "<p>БИБ-1 10.0.0.1</p>".repeat(10000) + "</body></html>");
        assertThat(stream(source, 32768)).isEqualTo(previous(source));
    }

    @Test
    void saxDocxPreservesTextAndOffsetsForSplitRunsTablesTabsAndHyperlinks() throws Exception {
        var entries = TikaSourceReaderFormatContractIT.docxEntries("unused");
        entries.put("word/document.xml", """
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                <w:body><w:p><w:r><w:t>😀БИБ-1 </w:t></w:r><w:r><w:t>section</w:t></w:r></w:p>
                <w:p><w:r><w:t>hxxps[:]</w:t></w:r><w:r><w:t>//example[.]org/api?q=1</w:t></w:r><w:r><w:tab/><w:t>10.0.0.1</w:t><w:br/><w:t>next</w:t></w:r></w:p>
                <w:tbl><w:tr><w:tc><w:p><w:r><w:t>one.test</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>two.test</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
                <w:p><w:hyperlink><w:r><w:t>visible.test</w:t></w:r></w:hyperlink></w:p>
                </w:body></w:document>
                """);
        Path source = directory.resolve("source.docx");
        TikaSourceReaderFormatContractIT.writeZip(source, entries);
        assertThat(stream(source, 262144)).isEqualTo(previous(source));
    }

    @Test
    void excessiveHtmlDepthIsRejectedBeforeSilentParserStackTruncation() throws Exception {
        Path source = directory.resolve("deep.html");
        Files.writeString(source, "<html><body>" + "<div>".repeat(300) + "10.0.0.1" + "</div>".repeat(300) + "</body></html>");
        assertThatThrownBy(() -> stream(source, 32768)).hasRootCauseMessage("HTML nesting exceeds admitted parser working limit");
    }

    @Test
    void denseRepairRegionRejectsAnExcessiveNodeGraph() throws Exception {
        Path source = directory.resolve("dense.html");
        Files.writeString(source, "<html><body><b>" + "x<i/>".repeat(5000) + "</b></body></html>");
        assertThatThrownBy(() -> stream(source, 32768))
                .hasRootCauseMessage("HTML node count exceeds admitted parser working limit");
    }

    @Test
    void regionReaderDistinguishesExactEofFromOverLimitAndResetsOnlyOnProgress() throws Exception {
        char[] chars = new char[4];
        try (var reader = new StreamingHtmlParser.RegionReader(new java.io.StringReader("abcd"), 4)) {
            assertThat(reader.read(chars, 0, 4)).isEqualTo(4);
            assertThat(new String(chars)).isEqualTo("abcd");
            assertThat(reader.read(chars, 0, 0)).isZero();
            assertThat(reader.read(chars, 0, 4)).isEqualTo(-1);
            assertThatThrownBy(() -> reader.read(chars, -1, 0)).isInstanceOf(IndexOutOfBoundsException.class);
        }
        try (var reader = new StreamingHtmlParser.RegionReader(new java.io.StringReader("abcdefgh"), 4)) {
            assertThat(reader.read(chars, 0, 4)).isEqualTo(4);
            reader.progress();
            assertThat(reader.read(chars, 0, 4)).isEqualTo(4);
            assertThat(new String(chars)).isEqualTo("efgh");
        }
        var source = new java.io.StringReader("abcde");
        try (var reader = new StreamingHtmlParser.RegionReader(source, 4)) {
            assertThat(reader.read(chars, 0, 4)).isEqualTo(4);
            assertThatThrownBy(() -> reader.read(chars, 0, 4))
                    .hasMessage("HTML region exceeds admitted parser working limit");
        }
        assertThatThrownBy(source::read).hasMessage("Stream closed");
    }

    @Test
    void saxDocxPreservesHeaderFooterAndFootnoteOrdering() throws Exception {
        var entries = TikaSourceReaderFormatContractIT.docxEntries("unused");
        String word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
        String relationship = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
        entries.put("[Content_Types].xml", entries.get("[Content_Types].xml").replace("</Types>", """
                <Override PartName="/word/header.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml"/>
                <Override PartName="/word/footer.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"/>
                <Override PartName="/word/footnotes.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footnotes+xml"/>
                </Types>
                """));
        entries.put("word/_rels/document.xml.rels", """
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="header" Type="%s/header" Target="header.xml"/>
                <Relationship Id="footer" Type="%s/footer" Target="footer.xml"/>
                <Relationship Id="notes" Type="%s/footnotes" Target="footnotes.xml"/>
                </Relationships>
                """.formatted(relationship, relationship, relationship));
        entries.put("word/document.xml", """
                <w:document xmlns:w="%s" xmlns:r="%s"><w:body>
                <w:p><w:r><w:t>body.test</w:t><w:footnoteReference w:id="1"/></w:r></w:p>
                <w:sectPr><w:headerReference w:type="default" r:id="header"/><w:footerReference w:type="default" r:id="footer"/></w:sectPr>
                </w:body></w:document>
                """.formatted(word, relationship));
        entries.put("word/header.xml", "<w:hdr xmlns:w=\"" + word + "\"><w:p><w:r><w:t>header.test</w:t></w:r></w:p></w:hdr>");
        entries.put("word/footer.xml", "<w:ftr xmlns:w=\"" + word + "\"><w:p><w:r><w:t>footer.test</w:t></w:r></w:p></w:ftr>");
        entries.put("word/footnotes.xml", "<w:footnotes xmlns:w=\"" + word + "\"><w:footnote w:id=\"1\"><w:p><w:r><w:t>footnote.test</w:t></w:r></w:p></w:footnote></w:footnotes>");
        Path source = directory.resolve("related.docx");
        TikaSourceReaderFormatContractIT.writeZip(source, entries);
        assertThat(stream(source, 262144)).isEqualTo(previous(source)).contains("header.test", "footer.test", "footnote.test");
    }

    @Test
    void oversizedUnclosedRegionAndEmbeddedHtmlFailInsteadOfReturningPartialText() throws Exception {
        Path source = directory.resolve("large.html");
        Files.writeString(source, "<html><body><p>" + "a".repeat(32769));
        assertThatThrownBy(() -> stream(source, 32768)).hasRootCauseMessage("HTML region exceeds admitted parser working limit");
        Files.writeString(source, "<html><body><iframe srcdoc='10.0.0.1'></iframe></body></html>");
        assertThatThrownBy(() -> stream(source, 32768)).hasRootCauseMessage("Embedded HTML attributes require a bounded separate document");
        Files.writeString(source, "<html><body><iframe src=' DATA:text/html,10.0.0.1'></iframe></body></html>");
        assertThatThrownBy(() -> stream(source, 32768)).hasRootCauseMessage("Embedded HTML attributes require a bounded separate document");
    }

    private static String stream(Path source, int limit) {
        var output = new StringWriter();
        new TikaSourceReader(StandardCharsets.UTF_8, new DiagnosticFactory(Clock.systemUTC()), limit).readText(source, output);
        return output.toString();
    }

    private static String previous(Path source) throws Exception {
        var output = new StringWriter();
        var metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, source.getFileName().toString());
        var context = new ParseContext();
        context.set(org.apache.tika.detect.EncodingDetector.class, (input, values) -> StandardCharsets.UTF_8);
        try (var input = Files.newInputStream(source)) {
            new AutoDetectParser().parse(input, new BodyContentHandler(output), metadata, context);
        }
        return output.toString();
    }
}
