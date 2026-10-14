package com.iocextractor.adapter.out.regex;

import com.iocextractor.application.tck.junit.ContractTest;
import com.iocextractor.domain.extract.PatternEngine;
import com.iocextractor.domain.extract.Span;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@ContractTest
class PatternEngineContractTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void exposesStableEngineIdentity(EngineCase engineCase) {
        assertThat(engineCase.engine().id()).isEqualTo(engineCase.id());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void compiledPatternIsReusableAndReturnsOrderedHalfOpenSpans(EngineCase engineCase) {
        PatternEngine.Compiled compiled = engineCase.engine().compile("\\bIOC-\\d+\\b");

        assertThat(compiled.findAll(new StringBuilder("before IOC-7, IOC-42 after")))
                .containsExactly(
                        new Span(7, 12, "IOC-7"),
                        new Span(14, 20, "IOC-42"));
        assertThat(compiled.findAll("IOC-9"))
                .containsExactly(new Span(0, 5, "IOC-9"));
        assertThat(compiled.findAll("no indicators here")).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void lazyCursorUsesAbsoluteUtf16OffsetsWithoutMaterializingTheSource(EngineCase engineCase) {
        var text = new CharSequence() {
            private final String value = "😀before IOC-7, IOC-42 after";
            public int length() { return value.length(); }
            public char charAt(int index) { return value.charAt(index); }
            public CharSequence subSequence(int start, int end) { return value.substring(start, end); }
            public String toString() { throw new AssertionError("Whole source must not be copied"); }
        };
        var cursor = engineCase.engine().compile("\\bIOC-\\d+\\b").matches(text);
        assertThat(cursor.next()).isTrue();
        assertThat(new Span(cursor.start(), cursor.end(), cursor.value())).isEqualTo(new Span(9, 14, "IOC-7"));
        assertThat(cursor.next()).isTrue();
        assertThat(new Span(cursor.start(), cursor.end(), cursor.value())).isEqualTo(new Span(16, 22, "IOC-42"));
        assertThat(cursor.next()).isFalse();
    }

    private static Stream<EngineCase> engines() {
        return Stream.of(
                new EngineCase("re2j", new Re2jPatternEngine()),
                new EngineCase("jdk", new JdkRegexPatternEngine()));
    }

    private record EngineCase(String id, PatternEngine engine) {

        @Override
        public String toString() {
            return id;
        }
    }
}
