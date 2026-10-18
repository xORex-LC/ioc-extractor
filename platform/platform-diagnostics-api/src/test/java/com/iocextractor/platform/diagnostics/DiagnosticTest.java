package com.iocextractor.platform.diagnostics;

import com.iocextractor.platform.diagnostics.TestCodes;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiagnosticTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-06-21T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void code_equality_is_owned_by_the_consumer_rather_than_the_id_string() {
        class IdentityCode implements DiagnosticCode {
            public String id() { return "consumer/same-id"; }
            public DiagnosticSeverity defaultSeverity() { return DiagnosticSeverity.WARN; }
            public DiagnosticImpact impact() { return DiagnosticImpact.ELEMENT; }
        }
        record ValueCode(String id) implements DiagnosticCode {
            public DiagnosticSeverity defaultSeverity() { return DiagnosticSeverity.WARN; }
            public DiagnosticImpact impact() { return DiagnosticImpact.ELEMENT; }
        }
        var firstCode = new IdentityCode();
        var first = Diagnostic.builder(firstCode, FIXED_CLOCK).build();
        var sameId = Diagnostic.builder(new IdentityCode(), FIXED_CLOCK).build();
        var sameObject = Diagnostic.builder(firstCode, FIXED_CLOCK).build();
        var valueCode = Diagnostic.builder(new ValueCode(firstCode.id()), FIXED_CLOCK).build();
        var equalValueCode = Diagnostic.builder(new ValueCode(firstCode.id()), FIXED_CLOCK).build();

        assertThat(first).isNotEqualTo(sameId).isEqualTo(sameObject).hasSameHashCodeAs(sameObject);
        assertThat(valueCode).isEqualTo(equalValueCode).hasSameHashCodeAs(equalValueCode);
        assertThat(new java.util.HashSet<>(java.util.List.of(first, sameId, sameObject, valueCode, equalValueCode)))
                .hasSize(3);
    }

    @Test
    void reusing_a_builder_copies_its_map_but_keeps_context_values_and_cause_by_reference() {
        var value = new java.util.ArrayList<>(java.util.List.of("first"));
        var cause = new IllegalStateException("source failed");
        var builder = Diagnostic.builder(TestCodes.ITEM_SKIPPED, FIXED_CLOCK)
                .with("rows", value).cause(cause);
        var first = builder.build();

        var second = builder.with("extra", 2).severity(DiagnosticSeverity.ERROR).build();

        assertThat(first.context()).containsOnlyKeys("rows");
        assertThat(first.context().get("rows")).isSameAs(value);
        assertThat(first.cause()).containsSame(cause);
        assertThat(first.severity()).isEqualTo(DiagnosticSeverity.WARN);
        assertThat(second.context()).containsEntry("extra", 2);
        assertThat(second.severity()).isEqualTo(DiagnosticSeverity.ERROR);
    }

    @Test
    void failed_bulk_context_addition_keeps_the_entries_accepted_before_the_null() {
        var values = new java.util.LinkedHashMap<String, Object>();
        values.put("accepted", 1);
        values.put("invalid", null);
        values.put("unvisited", 2);
        var builder = Diagnostic.builder(TestCodes.ITEM_SKIPPED, FIXED_CLOCK);

        assertThatNullPointerException().isThrownBy(() -> builder.context(values));

        assertThat(builder.build().context()).containsExactlyEntriesOf(java.util.Map.of("accepted", 1));
    }

    @Test
    void different_codes_and_contexts_remain_distinct_in_value_collections() {
        var first = Diagnostic.builder(TestCodes.ITEM_SKIPPED, FIXED_CLOCK).with("row", 1).build();
        var otherContext = Diagnostic.builder(TestCodes.ITEM_SKIPPED, FIXED_CLOCK).with("row", 2).build();
        var otherCode = Diagnostic.builder(TestCodes.EMPTY_TEXT, FIXED_CLOCK).with("row", 1).build();
        assertThat(new java.util.HashSet<>(java.util.List.of(first, otherContext, otherCode))).hasSize(3);
        assertThat(first).isNotEqualTo(otherContext).isNotEqualTo(otherCode).isNotEqualTo(null);
    }

    @Test
    void validates_consumer_metadata_without_imposing_an_id_naming_scheme() {
        record Code(String id, DiagnosticSeverity defaultSeverity, DiagnosticImpact impact) implements DiagnosticCode { }
        var code = new Code("import/bad-row", DiagnosticSeverity.WARN, DiagnosticImpact.ELEMENT);
        assertThat(Diagnostic.builder(code, FIXED_CLOCK).build().code()).isSameAs(code);
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException().isThrownBy(() ->
                Diagnostic.builder(new Code("  ", DiagnosticSeverity.WARN, DiagnosticImpact.ELEMENT), FIXED_CLOCK));
        for (var invalid : java.util.List.of(new Code(null, DiagnosticSeverity.WARN, DiagnosticImpact.ELEMENT),
                new Code("condition", null, DiagnosticImpact.ELEMENT), new Code("condition", DiagnosticSeverity.WARN, null))) {
            assertThatNullPointerException().isThrownBy(() -> Diagnostic.builder(invalid, FIXED_CLOCK));
        }
    }

    @Test
    void builder_uses_default_severity_and_clock_timestamp() {
        var diagnostic = Diagnostic.builder(TestCodes.READ_FAILED, FIXED_CLOCK)
                .with("source", "input.html")
                .with("reason", "denied")
                .build();

        assertThat(diagnostic.code()).isEqualTo(TestCodes.READ_FAILED);
        assertThat(diagnostic.severity()).isEqualTo(DiagnosticSeverity.FATAL);
        assertThat(diagnostic.timestamp()).isEqualTo(Instant.parse("2026-06-21T00:00:00Z"));
        assertThat(diagnostic.context()).containsEntry("source", "input.html");
    }

    @Test
    void factory_centralizes_the_clock() {
        var factory = new DiagnosticFactory(FIXED_CLOCK);

        var diagnostic = factory.create(TestCodes.EMPTY_TEXT)
                .with("source", "empty.html")
                .build();

        assertThat(diagnostic.timestamp()).isEqualTo(FIXED_CLOCK.instant());
    }

    @Test
    void context_is_copied_and_immutable() {
        var sourceContext = new java.util.LinkedHashMap<String, Object>();
        sourceContext.put("source", "input.html");

        var diagnostic = Diagnostic.builder(TestCodes.EMPTY_TEXT, FIXED_CLOCK)
                .context(sourceContext)
                .build();
        sourceContext.put("source", "mutated.html");

        assertThat(diagnostic.context()).containsEntry("source", "input.html");
        assertThatThrownBy(() -> diagnostic.context().put("new", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void equality_includes_severity_but_ignores_time_and_cause() {
        var first = Diagnostic.builder(TestCodes.READ_FAILED, FIXED_CLOCK)
                .with("source", "input.html")
                .with("reason", "denied")
                .cause(new IllegalStateException("first"))
                .build();
        var second = Diagnostic.builder(TestCodes.READ_FAILED,
                        Clock.fixed(Instant.parse("2026-06-22T00:00:00Z"), ZoneOffset.UTC))
                .severity(DiagnosticSeverity.FATAL)
                .with("source", "input.html")
                .with("reason", "denied")
                .cause(new IllegalArgumentException("second"))
                .build();

        assertThat(second).isEqualTo(first);
        assertThat(second).hasSameHashCodeAs(first);
        var lowerSeverity = Diagnostic.builder(TestCodes.READ_FAILED, FIXED_CLOCK)
                .severity(DiagnosticSeverity.ERROR).context(first.context()).build();
        assertThat(lowerSeverity).isNotEqualTo(first);
    }

    @Test
    void cause_is_optional() {
        var cause = new IllegalStateException("failed");

        var diagnostic = Diagnostic.builder(TestCodes.READ_FAILED, FIXED_CLOCK)
                .with("source", "input.html")
                .with("reason", "denied")
                .cause(cause)
                .build();

        assertThat(diagnostic.cause()).contains(cause);
    }

    @Test
    void builder_rejects_nulls() {
        var builder = Diagnostic.builder(TestCodes.READ_FAILED, FIXED_CLOCK);
        assertThatNullPointerException().isThrownBy(() -> new DiagnosticFactory(null));
        assertThatNullPointerException().isThrownBy(() -> new DiagnosticFactory(FIXED_CLOCK).create(null));
        assertThatNullPointerException().isThrownBy(() -> builder.severity(null));
        assertThatNullPointerException().isThrownBy(() -> builder.cause(null));
        assertThatNullPointerException().isThrownBy(() -> builder.context(null));
        assertThatNullPointerException()
                .isThrownBy(() -> Diagnostic.builder(null, FIXED_CLOCK));
        assertThatNullPointerException()
                .isThrownBy(() -> Diagnostic.builder(TestCodes.READ_FAILED, null));
        assertThatNullPointerException()
                .isThrownBy(() -> Diagnostic.builder(TestCodes.READ_FAILED, FIXED_CLOCK)
                        .with(null, "value"));
        assertThatNullPointerException()
                .isThrownBy(() -> Diagnostic.builder(TestCodes.READ_FAILED, FIXED_CLOCK)
                        .with("key", null));
        assertThatNullPointerException()
                .isThrownBy(() -> {
                    var values = new HashMap<String, Object>();
                    values.put("key", "value");
                    values.put("other", null);
                    Diagnostic.builder(TestCodes.READ_FAILED, FIXED_CLOCK).context(values);
                });
    }
}
