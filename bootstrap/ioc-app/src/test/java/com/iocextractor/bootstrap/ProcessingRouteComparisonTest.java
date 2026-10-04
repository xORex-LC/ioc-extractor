package com.iocextractor.bootstrap;

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
    void diagnosticAdmissionAnchorsSurroundTheActualWriterGuard() throws Exception {
        String type = "com/iocextractor/adapter/out/store/jdbc/JdbcWriterAdmission";
        byte[] bytes;
        try (var source = getClass().getClassLoader().getResourceAsStream(type + ".class")) {
            bytes = ComparisonDiagnostics.instrument(type, java.util.Objects.requireNonNull(source).readAllBytes());
        }
        Class<?> woven = new ClassLoader(getClass().getClassLoader()) {
            Class<?> loadProbe() { return defineClass(type.replace('/', '.'), bytes, 0, bytes.length); }
        }.loadProbe();
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
        }
    }
}
