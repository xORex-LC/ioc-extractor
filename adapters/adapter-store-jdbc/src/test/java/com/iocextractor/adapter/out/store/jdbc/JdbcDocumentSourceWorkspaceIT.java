package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.tck.junit.IntegrationTest;
import com.iocextractor.domain.attribute.AttributionDecision;
import com.iocextractor.domain.attribute.MarkerSourceAttributor;
import com.iocextractor.domain.attribute.SourceMarker;
import com.iocextractor.domain.extract.ExtractionDecision;
import com.iocextractor.domain.extract.ExtractionDecisionStatus;
import com.iocextractor.domain.extract.MatchCursor;
import com.iocextractor.domain.extract.PatternEngine;
import com.iocextractor.domain.extract.RegexIndicatorExtractor;
import com.iocextractor.domain.extract.Span;
import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.domain.refang.RefangRule;
import com.iocextractor.domain.refang.ReplacementRefanger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Physical source spool, with a separate finite-batch oracle and page/transaction boundary cases. */
@IntegrationTest
@Timeout(20)
class JdbcDocumentSourceWorkspaceIT {
    @TempDir Path directory;
    private static final DocumentPreparationLimits LIMITS = new DocumentPreparationLimits(
            4L * 1024 * 1024, 64, 4096, 1024, 16L * 1024 * 1024, 16L * 1024 * 1024, 128);

    @Test
    void orderedRefangPreservesUtf16IncludingSplitTokensAndUnpairedSurrogates() throws Exception {
        String input = "x".repeat(4093) + "😀hxxps[:]//host[.]test/a\ud800" + "x".repeat(4096) + "[.] tail";
        var rules = List.of(new RefangRule("hxxps", "https"), new RefangRule("[:]", ":"), new RefangRule("[.]", "."),
                new RefangRule("", "!"), new RefangRule("!!", "!"));
        var refanger = new ReplacementRefanger(rules);
        var expected = refanger.refang(input);
        try (var source = source(input)) {
            assertThat(refanger.refang(source)).isEqualTo(expected.decisions());
            assertThat(copy(source.text())).isEqualTo(expected.text());
            assertThatThrownBy(() -> source.text().toString()).hasMessageContaining("materialization");
            assertThat(source.text().subSequence(4092, 4099).toString()).isEqualTo(expected.text().substring(4092, 4099));
        }
        assertThat(directory).isEmptyDirectory();
    }

    @Test
    void globalTypePriorityAndMarkersMatchBatchOracleAcrossPagesAndCommits() throws Exception {
        var patterns = new LinkedHashMap<IndicatorType, String>();
        patterns.put(IndicatorType.URL, "https://[a-z0-9.]+/[a-z0-9]+");
        patterns.put(IndicatorType.IPV4, "10\\.0\\.0\\.[0-9]+");
        patterns.put(IndicatorType.DOMAIN, "[a-z0-9]+\\.test");
        var engine = engine();
        var extractor = new RegexIndicatorExtractor(engine, patterns);
        var attributor = new MarkerSourceAttributor(engine, List.of("БИБ-[0-9]+ extra", "БИБ-[0-9]+", "Письмо X"));
        String text = "no-marker.test " + "x ".repeat(2040) + "😀БИБ-1 extra https://one.test/api 10.0.0.1 "
                + "БИБ-2\u00a0extra " + "two.test 10.0.0.2 ".repeat(1600) + "Письмо X https://last.test/end";
        var expected = extractor.extract(text);
        var attributed = attributor.attribute(text, expected.indicators());
        try (var source = source(text)) {
            extractor.extract(source.text(), source.maximumMatchCharacters(), source);
            assertThat(source.decisions().snapshot()).isEqualTo(expected.decisions());
            assertThat(source.indicators().snapshot()).isEqualTo(expected.indicators());
            var markers = attributor.markers(source.text(), source.maximumMatchCharacters());
            var selected = new ArrayList<SourceMarker>();
            while (markers.next()) { selected.add(markers.value()); }
            assertThat(selected).isEqualTo(attributed.markers());
            try (var rows = source.indicators().open()) {
                int ordinal = 0;
                while (rows.next()) {
                    assertThat(rows.value()).isEqualTo(attributed.decisions().get(ordinal).rawIndicator());
                    source.attribute(attributed.decisions().get(ordinal++));
                }
            }
            assertThat(source.attributions().snapshot()).isEqualTo(attributed.decisions());
        }
        assertThat(directory).isEmptyDirectory();
    }

    @Test
    void overlapPredecessorHandlesContainmentAdjacencyAndZeroLength() throws Exception {
        try (var source = source("bounded")) {
            source.record(new ExtractionDecision(IndicatorType.DOMAIN, "one", new Span(10, 20, "one"), ExtractionDecisionStatus.ACCEPTED));
            source.record(new ExtractionDecision(IndicatorType.IPV4, "two", new Span(30, 40, "two"), ExtractionDecisionStatus.ACCEPTED));
            for (int[] span : new int[][]{{0, 11}, {11, 12}, {0, 50}, {19, 31}, {35, 50}}) {
                assertThat(source.overlaps(span[0], span[1])).isTrue();
            }
            for (int[] span : new int[][]{{0, 10}, {20, 30}, {40, 50}, {15, 15}}) {
                assertThat(source.overlaps(span[0], span[1])).isFalse();
            }
            assertThat(source.acceptedType(10, 20)).contains(IndicatorType.DOMAIN);
            assertThat(source.acceptedType(11, 12)).isEmpty();
        }
    }

    @Test
    void cursorOwnershipInvalidValuesAndQuotaFailureReleaseScratch() throws Exception {
        try (var source = source("bounded")) {
            source.record(new ExtractionDecision(IndicatorType.DOMAIN, "one", new Span(0, 3, "one"), ExtractionDecisionStatus.ACCEPTED));
            assertThatThrownBy(() -> source.attribute(new AttributionDecision(
                    new com.iocextractor.domain.extract.RawIndicator("one", IndicatorType.DOMAIN, 0), Optional.empty())))
                    .hasMessageContaining("current accepted");
            try (var cursor = source.indicators().open()) {
                assertThatThrownBy(cursor::value).hasMessageContaining("no current");
                assertThatThrownBy(() -> source.decisions().open()).hasMessageContaining("Only one");
                assertThat(cursor.next()).isTrue(); assertThat(cursor.next()).isFalse();
                assertThatThrownBy(cursor::value).hasMessageContaining("no current");
            }
            assertThatThrownBy(() -> source.record(new ExtractionDecision(IndicatorType.DOMAIN, "one",
                    new Span(4, 1029, "я".repeat(1025)), ExtractionDecisionStatus.ACCEPTED)))
                    .hasMessageContaining("field limit");
        }
        assertThat(directory).isEmptyDirectory();
        var tiny = new DocumentPreparationLimits(4L * 1024 * 1024, 64, 4096, 1024, 65536, 65536, 128);
        try (var source = new JdbcDocumentSourceWorkspace(directory, tiny, () -> { }); var writer = source.writer()) {
            assertThatThrownBy(() -> writer.write("x".repeat(8193))).hasMessageContaining("Decoded source text");
        }
        assertThat(directory).isEmptyDirectory();
    }

    @Test
    void limitsAreCheckedBeforeMatchingOrMarkerValueMaterialization() throws Exception {
        try (var source = source("x".repeat(1025))) {
            assertThatThrownBy(() -> new RegexIndicatorExtractor(engine(), Map.of(IndicatorType.DOMAIN, "x+"))
                    .extract(source.text(), source.maximumMatchCharacters(), source)).hasMessageContaining("field limit");
            var markers = new MarkerSourceAttributor(engine(), List.of("x+")).markers(source.text(), 1024);
            assertThatThrownBy(markers::next).hasMessageContaining("field limit");
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"abc", "a\u00a0bc"})
    void zeroLengthMarkerViewsRetainExactlyTheBatchMultiplicity(String text) {
        var attributor = new MarkerSourceAttributor(engine(), List.of("^", "$", ""));
        var expected = attributor.attribute(text, List.of()).markers();
        var actual = new ArrayList<SourceMarker>();
        var cursor = attributor.markers(text, 1024);
        while (cursor.next()) { actual.add(cursor.value()); }
        assertThat(actual).containsExactlyElementsOf(expected);
    }

    @Test
    void sourceWriterAndSlicesRejectUseOutsideTheirOwnedLifetime() throws Exception {
        var source = new JdbcDocumentSourceWorkspace(directory, LIMITS, () -> { });
        assertThatThrownBy(source::text).hasMessageContaining("not closed");
        var writer = source.writer();
        assertThatThrownBy(source::writer).hasMessageContaining("already supplied");
        assertThatThrownBy(source::text).hasMessageContaining("not closed");
        writer.write(new char[]{'a', "😀".charAt(0), 'b'}, 0, 3);
        writer.close();
        writer.close();
        assertThatThrownBy(() -> writer.write('x')).hasMessageContaining("writer is closed");
        var text = source.text();
        var slice = text.subSequence(0, 3).subSequence(1, 3);
        assertThat(slice.length()).isEqualTo(2);
        assertThat(slice.charAt(1)).isEqualTo('b');
        assertThat(slice.toString()).isEqualTo(copy(text).substring(1, 3));
        assertThatThrownBy(() -> slice.charAt(2)).isInstanceOf(IndexOutOfBoundsException.class);
        source.close(); source.close();
        assertThatThrownBy(source::text).hasMessageContaining("workspace is closed");
        assertThatThrownBy(() -> slice.charAt(0)).hasMessageContaining("text is closed");
        assertThat(directory).isEmptyDirectory();
    }

    @Test
    void currentOccurrenceIdentityAndCursorPurposeGuardAttribution() throws Exception {
        try (var source = source("abc")) {
            var occurrence = new com.iocextractor.domain.extract.RawIndicator("abc", IndicatorType.DOMAIN, 0);
            var attribution = new AttributionDecision(occurrence, Optional.empty());
            source.record(new ExtractionDecision(IndicatorType.DOMAIN, "abc", new Span(0, 3, "abc"), ExtractionDecisionStatus.ACCEPTED));
            source.record(new ExtractionDecision(IndicatorType.DOMAIN, "abc", new Span(3, 3, ""), ExtractionDecisionStatus.ACCEPTED));
            assertThatThrownBy(() -> source.record(new ExtractionDecision(IndicatorType.DOMAIN, "other",
                    new Span(4, 4, ""), ExtractionDecisionStatus.ACCEPTED))).hasMessageContaining("Multiple patterns");
            try (var decisions = source.decisions().open()) {
                assertThat(decisions.next()).isTrue();
                assertThatThrownBy(() -> source.attribute(attribution)).hasMessageContaining("current accepted");
            }
            var rows = source.indicators().open();
            assertThatThrownBy(() -> source.attribute(attribution)).hasMessageContaining("current accepted");
            assertThat(rows.next()).isTrue();
            assertThatThrownBy(() -> source.attribute(new AttributionDecision(
                    new com.iocextractor.domain.extract.RawIndicator("other", IndicatorType.DOMAIN, 0), Optional.empty())))
                    .hasMessageContaining("does not describe");
            source.attribute(attribution);
            rows.close(); rows.close();
            assertThatThrownBy(rows::next).hasMessageContaining("cursor is closed");
            assertThatThrownBy(rows::value).hasMessageContaining("no current");
            assertThat(source.attributions().snapshot()).contains(attribution);
            // Closing the owner also closes an early-exit cursor.
            var owned = source.indicators().open();
            assertThat(owned.next()).isTrue();
        }
        assertThat(directory).isEmptyDirectory();
    }

    @Test
    void utf8FieldAdmissionAndLiteralLimitsFailBeforeRecordingOrRewriting() throws Exception {
        try (var source = source("abc")) {
            assertThatThrownBy(() -> source.record(new ExtractionDecision(IndicatorType.DOMAIN, "abc",
                    new Span(0, 513, "я".repeat(513)), ExtractionDecisionStatus.ACCEPTED))).hasMessageContaining("field limit");
            assertThat(source.decisions().size()).isZero();
            assertThatThrownBy(() -> source.replace(new RefangRule("x".repeat(1025), "y")))
                    .hasMessageContaining("literal exceeds");
            assertThatThrownBy(() -> source.replace(new RefangRule("x", "y".repeat(1025))))
                    .hasMessageContaining("literal exceeds");
            assertThat(copy(source.text())).isEqualTo("abc");
        }
    }

    @Test
    void interruptionReleasesRewriteAndCursorScratchWithoutClearingTheSignal() throws Exception {
        try (var source = source("abc")) {
            try {
                Thread.currentThread().interrupt();
                assertThatThrownBy(() -> source.replace(new RefangRule("a", "x"))).hasMessageContaining("interrupted");
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally { Thread.interrupted(); }
            try (var rows = source.indicators().open()) {
                try {
                    Thread.currentThread().interrupt();
                    assertThatThrownBy(rows::next).hasMessageContaining("interrupted");
                } finally { Thread.interrupted(); }
            }
        }
        assertThat(directory).isEmptyDirectory();
    }

    @Test
    void unreadOrMalformedTextAndPrematureFileTruncationCannotSupplyPartialValues() throws Exception {
        try (var spool = new DocumentTextSpool(directory, LIMITS, () -> { })) {
            assertThatThrownBy(spool::text).hasMessageContaining("not closed");
        }
        Path path = directory.resolve("malformed.utf16");
        Files.write(path, new byte[]{1});
        assertThatThrownBy(() -> new PagedDocumentText(path, 1024)).hasMessageContaining("invalid UTF-16 length");
        Files.write(path, new byte[]{0, 97, 0, 98});
        try (var text = new PagedDocumentText(path, 1024)) {
            Files.write(path, new byte[0]);
            assertThatThrownBy(() -> text.charAt(0)).hasRootCauseMessage("Source spool ended before its pinned length");
            text.close(); text.close();
            assertThatThrownBy(text::length).hasMessageContaining("closed");
        }
    }

    @Test
    void oversizedUtf16OffsetsAreRejectedWithoutReadingOrAllocatingTheSource() throws Exception {
        Path path = directory.resolve("oversized.utf16");
        try {
            try (var channel = java.nio.channels.FileChannel.open(path,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE)) {
                // A sparse file consumes one block, rather than retaining a multi-gigabyte fixture.
                channel.position(2L * Integer.MAX_VALUE + 1);
                channel.write(java.nio.ByteBuffer.wrap(new byte[]{0}));
            }
            assertThatThrownBy(() -> new PagedDocumentText(path, 1024))
                    .hasMessageContaining("invalid UTF-16 length");
        } finally { Files.deleteIfExists(path); }
    }

    @Test
    void closingTheTextOwnerTwiceRemovesBothFilesAndRejectsEveryNewOperation() throws Exception {
        var spool = new DocumentTextSpool(directory, LIMITS, () -> { });
        try (var writer = spool.writer()) { writer.write("abc"); }
        assertThat(spool.text().toString()).isEqualTo("abc");
        spool.close(); spool.close();
        assertThatThrownBy(spool::writer).hasMessage("Source spool is closed");
        assertThatThrownBy(spool::text).hasMessage("Source spool is closed");
        assertThatThrownBy(() -> spool.replace(new RefangRule("a", "b"))).hasMessage("Source spool is closed");
        assertThat(directory).isEmptyDirectory();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"fatal", "runtime", "file"})
    void textCleanupPreservesWriterFailureAndStillRemovesTheSecondFile(String mode) throws Exception {
        Throwable primary = mode.equals("fatal") ? new AssertionError("writer close failed")
                : new IllegalStateException("writer close failed");
        var spool = new DocumentTextSpool(directory, LIMITS, () -> {
            if (mode.equals("file")) { return; }
            if (primary instanceof Error fatal) { throw fatal; }
            throw (RuntimeException) primary;
        });
        spool.writer().write("abc");
        Path text = directory.resolve("source-text.utf16");
        Files.delete(text); Files.createDirectory(text); Files.writeString(text.resolve("blocker"), "blocker");
        Path replacement = directory.resolve("source-refang.utf16"); Files.writeString(replacement, "scratch");
        assertThatThrownBy(spool::close).satisfies(failure -> {
            if (mode.equals("file")) { assertThat(failure).isInstanceOf(java.nio.file.DirectoryNotEmptyException.class); }
            else {
                assertThat(failure).isSameAs(primary);
                assertThat(failure.getSuppressed()).singleElement().isInstanceOf(java.nio.file.DirectoryNotEmptyException.class);
            }
        });
        assertThat(replacement).doesNotExist();
        spool.close();
    }

    @Test
    void failedScratchOpenAndCloseKeepCleanupFailuresAndRemoveOwnedText() throws Exception {
        Path blocker = directory.resolve("source-decisions.db");
        Files.createDirectory(blocker); Files.writeString(blocker.resolve("foreign"), "owned test blocker");
        assertThatThrownBy(() -> new JdbcDocumentSourceWorkspace(directory, LIMITS, () -> { }))
                .hasMessageContaining("initialize document source");
        Files.delete(blocker.resolve("foreign")); Files.delete(blocker);
        var failClose = new java.util.concurrent.atomic.AtomicBoolean();
        var source = new JdbcDocumentSourceWorkspace(directory, LIMITS, () -> {
            if (failClose.get()) { throw new AssertionError("fatal disk check"); }
        });
        var writer = source.writer(); writer.write("abc");
        failClose.set(true);
        Files.delete(blocker); Files.createDirectory(blocker);
        Files.writeString(blocker.resolve("foreign"), "owned test blocker");
        assertThatThrownBy(source::close).isInstanceOf(AssertionError.class).hasMessage("fatal disk check")
                .satisfies(failure -> assertThat(failure.getSuppressed()).singleElement()
                        .isInstanceOf(java.nio.file.DirectoryNotEmptyException.class));
        assertThat(directory.resolve("source-text.utf16")).doesNotExist();
        source.close();
    }

    private JdbcDocumentSourceWorkspace source(String input) throws Exception {
        var source = new JdbcDocumentSourceWorkspace(directory, LIMITS, () -> { });
        try (var writer = source.writer()) {
            // One-character writes deliberately split surrogate pairs and refang/regex/marker literals.
            for (int index = 0; index < input.length(); index++) { writer.write(input.charAt(index)); }
        }
        return source;
    }
    private static String copy(CharSequence text) {
        var result = new StringBuilder();
        for (int index = 0; index < text.length(); index++) { result.append(text.charAt(index)); }
        return result.toString();
    }
    private static PatternEngine engine() {
        return new PatternEngine() {
            public String id() { return "jdk-test"; }
            public Compiled compile(String regex) {
                var pattern = java.util.regex.Pattern.compile(regex);
                return new Compiled() {
                    public List<Span> findAll(CharSequence text) {
                        var matches = pattern.matcher(text);
                        var result = new ArrayList<Span>();
                        while (matches.find()) { result.add(new Span(matches.start(), matches.end(), matches.group())); }
                        return result;
                    }
                    public MatchCursor matches(CharSequence text) {
                        var matches = pattern.matcher(text);
                        return new MatchCursor() {
                            public boolean next() { return matches.find(); }
                            public int start() { return matches.start(); }
                            public int end() { return matches.end(); }
                            public String value() { return matches.group(); }
                        };
                    }
                };
            }
        };
    }
}
