package com.iocextractor.bootstrap;

import com.iocextractor.adapter.out.regex.Re2jPatternEngine;
import com.iocextractor.domain.attribute.AttributionOutcome;
import com.iocextractor.domain.attribute.MarkerSourceAttributor;
import com.iocextractor.domain.attribute.SourceAttributor;
import com.iocextractor.domain.extract.RawIndicator;
import com.iocextractor.domain.model.IndicatorType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import static org.mockito.Mockito.mock;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.lang.instrument.Instrumentation;
import com.iocextractor.domain.feature.NetworkAddressParser;
import com.iocextractor.application.artifact.ArtifactIdSequence;
import com.iocextractor.application.artifact.ArtifactIdStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies that the opt-in measurement probe cannot publish partial memory samples. */
class ProcessingRouteComparisonTest {

    @Test
    void validationCommitDoesNotCompleteAnUnstartedCanonicalOwnershipTimer() throws Exception {
        String fixture = ValidationCommitFixture.class.getName().replace('.', '/');
        byte[] bytes;
        try (var input = getClass().getClassLoader().getResourceAsStream(fixture + ".class")) {
            bytes = ComparisonDiagnostics.instrument(
                    "com/iocextractor/adapter/out/store/jdbc/JdbcCanonicalLifecycleWriter",
                    java.util.Objects.requireNonNull(input).readAllBytes());
        }
        Class<?> woven = new ClassLoader(getClass().getClassLoader()) {
            Class<?> loadProbe() { return defineClass(null, bytes, 0, bytes.length); }
        }.loadProbe();
        var constructor = woven.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object writer = constructor.newInstance();
        var validation = woven.getDeclaredMethod("validateRows", java.sql.Connection.class);
        validation.setAccessible(true);
        var connection = mock(java.sql.Connection.class);
        ComparisonDiagnostics.premain("", mock(Instrumentation.class));
        ComparisonDiagnostics.begin();
        String counters;
        try {
            validation.invoke(writer, connection);
        } finally {
            counters = ComparisonDiagnostics.end();
        }
        org.mockito.Mockito.verify(connection).commit();
        assertThat(counters).doesNotContain("canonical_writer_hold_nanos", "canonical_transactions");
    }

    public static final class ValidationCommitFixture {
        public void validateRows(java.sql.Connection connection) throws java.sql.SQLException {
            connection.commit();
        }
    }

    @Test
    void ordered_lookup_is_linear_and_unordered_lookup_is_logarithmic() throws Exception {
        var attributor = instrumentedAttributor();
        int sections = 400;
        int occurrences = 100_000;
        var text = new StringBuilder();
        var indicators = new ArrayList<RawIndicator>();
        for (int section = 0; section < sections; section++) {
            int position = text.length();
            text.append("SECTION-").append(section).append('\n');
            for (int index = 0; index < occurrences / sections; index++) {
                indicators.add(new RawIndicator("example.test", IndicatorType.DOMAIN, position));
            }
        }
        assertComparisons(attributor, text.toString(), indicators, occurrences + sections);
        Collections.shuffle(indicators, new Random(302_031L));
        int binarySearchBound = 32 - Integer.numberOfLeadingZeros(sections);
        assertComparisons(attributor, text.toString(), indicators, (long) occurrences * binarySearchBound);
    }

    private static void assertComparisons(SourceAttributor attributor, String text,
                                          List<RawIndicator> input, long upperBound) {
        ComparisonDiagnostics.premain("", mock(Instrumentation.class));
        ComparisonDiagnostics.begin();
        AttributionOutcome outcome;
        String counters;
        try {
            outcome = attributor.attribute(text, input);
        } finally {
            counters = ComparisonDiagnostics.end();
        }
        assertThat(outcome.decisions()).extracting(decision -> decision.rawIndicator())
                .containsExactlyElementsOf(input);
        assertThat(outcome.decisions()).allSatisfy(decision ->
                assertThat(decision.marker().orElseThrow().position()).isEqualTo(decision.rawIndicator().position()));
        long comparisons = Long.parseLong(counters.replaceAll(
                ".*attribution_marker_comparisons=(\\d+).*", "$1"));
        assertThat(comparisons).isPositive().isLessThanOrEqualTo(upperBound);
    }

    private static SourceAttributor instrumentedAttributor() throws Exception {
        String prefix = MarkerSourceAttributor.class.getName();
        var loader = new ClassLoader(MarkerSourceAttributor.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.equals(prefix) && !name.startsWith(prefix + "$")) {
                    return super.loadClass(name, resolve);
                }
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    String path = name.replace('.', '/');
                    try (var source = getParent().getResourceAsStream(path + ".class")) {
                        byte[] bytes = ComparisonDiagnostics.instrument(path,
                                java.util.Objects.requireNonNull(source).readAllBytes());
                        loaded = defineClass(name, bytes, 0, bytes.length);
                    } catch (java.io.IOException failure) {
                        throw new ClassNotFoundException(name, failure);
                    }
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        };
        return (SourceAttributor) loader.loadClass(prefix)
                .getConstructor(com.iocextractor.domain.extract.PatternEngine.class, List.class)
                .newInstance(new Re2jPatternEngine(), List.of("SECTION-\\d+"));
    }

    @Test
    void diagnosticAdmissionAnchorsSurroundTheActualWriterGuard() throws Exception {
        String type = "com/iocextractor/adapter/out/store/jdbc/JdbcWriterAdmission";
        byte[] bytes;
        try (var source = getClass().getClassLoader().getResourceAsStream(type + ".class")) {
            bytes = ComparisonDiagnostics.instrument(type, java.util.Objects.requireNonNull(source).readAllBytes());
        }
        Class<?> woven = new ClassLoader(getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.startsWith(type.replace('/', '.'))) { return super.loadClass(name, resolve); }
                Class<?> loaded = findLoadedClass(name);
                if (loaded != null) { return loaded; }
                try (var input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                    byte[] implementation = name.equals(type.replace('/', '.')) ? bytes
                            : java.util.Objects.requireNonNull(input).readAllBytes();
                    return defineClass(name, implementation, 0, implementation.length);
                } catch (java.io.IOException failure) { throw new ClassNotFoundException(name, failure); }
            }
        }.loadClass(type.replace('/', '.'));
        Object admission = woven.getConstructor().newInstance();
        ComparisonDiagnostics.premain("", org.mockito.Mockito.mock(Instrumentation.class));
        ComparisonDiagnostics.begin();
        String counters;
        try {
            assertThat(woven.getMethod("execute", java.util.function.Supplier.class)
                    .invoke(admission, (java.util.function.Supplier<String>) () -> "written"))
                    .isEqualTo("written");
        } finally {
            counters = ComparisonDiagnostics.end();
        }
        assertThat(counters).contains("writer_admissions=1", "writer_admission_wait_nanos=",
                "writer_admission_hold_nanos=", "max_writer_admission_hold_nanos=");
    }

    @Test
    void diagnosticWeavingPreservesVerificationOfStaticViewSnapshotLambda() throws Exception {
        String type = "com/iocextractor/adapter/processing/camel/runtime/InvocationViews";
        byte[] bytes;
        try (var source = getClass().getClassLoader().getResourceAsStream(type + ".class")) {
            bytes = ComparisonDiagnostics.instrument(type, java.util.Objects.requireNonNull(source).readAllBytes());
        }
        Class<?> woven = new ClassLoader(getClass().getClassLoader()) {
            Class<?> loadProbe() { return defineClass(type.replace('/', '.'), bytes, 0, bytes.length); }
        }.loadProbe();
        assertThat(woven.getDeclaredMethods()).isNotEmpty();
    }

    @Test
    void diagnosticWeavingCountsRealParserCallsOnlyInsideMeasuredScope() throws Exception {
        String type = "com/iocextractor/domain/feature/NetworkAddressParser";
        byte[] bytes;
        try (var source = NetworkAddressParser.class.getResourceAsStream("NetworkAddressParser.class")) {
            bytes = ComparisonDiagnostics.instrument(type, java.util.Objects.requireNonNull(source).readAllBytes());
        }
        Class<?> woven = new ClassLoader(getClass().getClassLoader()) {
            Class<?> loadProbe() { return defineClass(type.replace('/', '.'), bytes, 0, bytes.length); }
        }.loadProbe();
        Object parser = woven.getConstructor().newInstance();
        var parse = woven.getMethod("parse", String.class);
        ComparisonDiagnostics.premain("", org.mockito.Mockito.mock(Instrumentation.class));
        parse.invoke(parser, "before.example.com");
        ComparisonDiagnostics.begin();
        String counters;
        try {
            var result = (NetworkAddressParser.Result) parse.invoke(parser, "https://192.0.2.44/path");
            assertThat(result.address().host()).isEqualTo("192.0.2.44");
            assertThat(result.address().hasPath()).isTrue();
            assertThat(((NetworkAddressParser.Result) parse.invoke(parser, "ftp://invalid")).failure())
                    .isEqualTo(NetworkAddressParser.FailureReason.UNSUPPORTED_SCHEME);
        } finally {
            counters = ComparisonDiagnostics.end();
        }
        parse.invoke(parser, "after.example.com");
        assertThat(counters).isEqualTo("parser_calls=2");
    }

    @Test
    void derivedCountersSeparateRequestsFromPolicyComputations() {
        ComparisonDiagnostics.premain("", org.mockito.Mockito.mock(Instrumentation.class));
        ComparisonDiagnostics.begin();
        ComparisonDiagnostics.derivedStart();
        ComparisonDiagnostics.classified();
        ComparisonDiagnostics.derivedFinish();
        ComparisonDiagnostics.derivedStart();
        ComparisonDiagnostics.derivedFinish();
        ComparisonDiagnostics.classified();
        assertThat(ComparisonDiagnostics.end()).contains("derived_classification_requests=2", "derived_classifications=1");
    }

    @Test
    void winnerInstrumentationIsValidForTheNestedAccumulator() throws Exception {
        String type = "com/iocextractor/application/artifact/policy/ArtifactOccurrenceSelector$Accumulator";
        byte[] bytes;
        try (var source = getClass().getClassLoader().getResourceAsStream(type + ".class")) {
            bytes = ComparisonDiagnostics.instrument(type, java.util.Objects.requireNonNull(source).readAllBytes());
        }
        Class<?> woven = new ClassLoader(getClass().getClassLoader()) {
            Class<?> loadProbe() { return defineClass(type.replace('/', '.'), bytes, 0, bytes.length); }
        }.loadProbe();
        assertThat(woven.getDeclaredMethods()).isNotEmpty();
    }

    @Test
    void incompleteDiagnosticTimingCannotProduceSuccessfulSnapshot() {
        ComparisonDiagnostics.premain("", org.mockito.Mockito.mock(Instrumentation.class));
        ComparisonDiagnostics.begin();
        ComparisonDiagnostics.start("preparation_nanos");
        assertThatThrownBy(ComparisonDiagnostics::end).isInstanceOf(IllegalStateException.class)
                .hasMessage("Preparation instrumentation did not complete");

        ComparisonDiagnostics.begin();
        ComparisonDiagnostics.start("preparation_nanos");
        ComparisonDiagnostics.reserved(new ArtifactIdSequence(ArtifactIdStrategy.ASCENDING, 100).reserve(4));
        ComparisonDiagnostics.finish("preparation_nanos");
        assertThat(ComparisonDiagnostics.end()).contains("preparation_nanos=", "reserved_ids=4",
                "reservation_ASCENDING_100_4=1");
    }

    @Test
    @Timeout(5)
    void samplerFailureReachesCaller() throws InterruptedException {
        var sampled = new CountDownLatch(1);
        var sampler = new ProcessingRouteComparison.PeakSampler(() -> {
            sampled.countDown();
            throw new IllegalStateException("synthetic status failure");
        });

        assertThatThrownBy(() -> {
            try (sampler) {
                assertThat(sampled.await(2, TimeUnit.SECONDS)).isTrue();
            }
        })
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Memory sampler failed")
                .hasRootCauseMessage("synthetic status failure");
    }

    @Test
    @Timeout(5)
    void samplerInterruptionReachesCaller() throws InterruptedException {
        var sampled = new CountDownLatch(1);
        var sampler = new ProcessingRouteComparison.PeakSampler(() -> {
            Thread.currentThread().interrupt();
            sampled.countDown();
            return 42;
        });

        assertThatThrownBy(() -> {
            try (sampler) {
                assertThat(sampled.await(2, TimeUnit.SECONDS)).isTrue();
            }
        })
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Memory sampler failed")
                .hasRootCauseInstanceOf(InterruptedException.class);
    }

    @Test
    @Timeout(5)
    void completedSamplerReturnsItsPeak() throws InterruptedException {
        var sampled = new CountDownLatch(1);
        var sampler = new ProcessingRouteComparison.PeakSampler(() -> {
            sampled.countDown();
            return 42;
        });

        try (sampler) {
            assertThat(sampled.await(2, TimeUnit.SECONDS)).isTrue();
            sampler.awaitFirstSample();
        }
        assertThat(sampler.peakHeapBytes()).isPositive();
        assertThat(sampler.peakCurrentRssKiB()).isPositive();
    }
}
