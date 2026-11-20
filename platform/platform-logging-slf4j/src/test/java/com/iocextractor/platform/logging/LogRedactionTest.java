package com.iocextractor.platform.logging;

import com.iocextractor.platform.logging.redaction.LogRedaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.MDC;

import java.util.function.Function;
import java.util.stream.Stream;

import static com.iocextractor.platform.logging.LogCapture.*;
import static org.assertj.core.api.Assertions.*;

@ResourceLock("slf4j-mdc-adapter")
class LogRedactionTest {
    private final LogCapture capture = new LogCapture();

    @AfterEach
    void releaseAppender() {
        capture.close();
    }

    @ParameterizedTest
    @MethodSource("maskedLevels")
    void explicitMaskWinsOverAmbientMdcAtEveryLevel(Function<org.slf4j.Logger, LogEvent> factory) {
        MDC.put(TOKEN.key(), "synthetic-ambient-secret");
        factory.apply(capture.logger).field(TOKEN, LogRedaction.fullMask()).log();
        var record = capture.output.list.getFirst();
        assertThat(fields(record)).containsEntry(TOKEN.key(), "[redacted]");
        assertThat(record.getMDCPropertyMap()).doesNotContainKey(TOKEN.key());
        assertThat(MDC.get(TOKEN.key())).isEqualTo("synthetic-ambient-secret");
    }

    static Stream<Function<org.slf4j.Logger, LogEvent>> maskedLevels() {
        return Stream.of(LogEvents::trace, LogEvents::debug, LogEvents::info, LogEvents::warn, LogEvents::error);
    }

    @Test
    void fullMaskDoesNotWidenNumericOrBooleanWireTypes() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                LogEvents.info(capture.logger).field(ITEMS, LogRedaction.fullMask()));
        assertThatIllegalArgumentException().isThrownBy(() ->
                LogEvents.info(capture.logger).field(RETRY, LogRedaction.fullMask()));
    }

    @ParameterizedTest
    @MethodSource("urlExamples")
    void urlTransformationKeepsTheQualifiedLexicalBehavior(String input, String expected) {
        assertThat(LogRedaction.urlComponents(input)).isEqualTo(expected);
    }

    static Stream<Arguments> urlExamples() {
        return Stream.of(Arguments.of(null, null), Arguments.of("", ""), Arguments.of("plain", "plain"),
                Arguments.of("https://u:p@example.test/p?q=secret#f", "https://<redacted>@example.test/p?<redacted>#f"),
                Arguments.of("u:p@example.test/p", "u:p@example.test/p"),
                Arguments.of("example.test/p?q=secret", "example.test/p?<redacted>"),
                Arguments.of("https://a@b@example.test/p", "https://<redacted>@example.test/p"),
                Arguments.of("https://example.test/secret#secret", "https://example.test/secret#secret"),
                Arguments.of("https://example.test/#f?q=secret", "https://example.test/#f?<redacted>"),
                Arguments.of("https://example.test/?a?b#f#z", "https://example.test/?<redacted>#f#z"),
                Arguments.of("https://example.test/p@visible", "https://example.test/p@visible"),
                Arguments.of("https://u:p@", "https://<redacted>@"),
                Arguments.of("?", "?<redacted>"),
                Arguments.of("https://u:p@example.test:443/p", "https://<redacted>@example.test:443/p"),
                Arguments.of("https://u:p@example.test?", "https://<redacted>@example.test?<redacted>"),
                Arguments.of("https://example.test#fragment", "https://example.test#fragment"));
    }
}
