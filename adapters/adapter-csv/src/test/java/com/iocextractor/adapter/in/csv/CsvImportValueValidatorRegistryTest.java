package com.iocextractor.adapter.in.csv;

import com.iocextractor.processing.classification.IndicatorClassifier;
import com.iocextractor.processing.model.ClassifiedIndicator;
import com.iocextractor.domain.classify.ClassificationDecision;
import com.iocextractor.domain.extract.ExtractionOutcome;
import com.iocextractor.domain.extract.RawIndicator;
import com.iocextractor.domain.feature.HostKind;
import com.iocextractor.domain.feature.IndicatorFeatures;
import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.domain.model.MaskMatch;
import com.iocextractor.domain.refang.RefangOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CsvImportValueValidatorRegistryTest {

    @Test
    void validatesWholeCellCarrierKindsThroughSharedStructuralConditions() {
        CsvImportValueValidatorRegistry validators = validators(Map.of(
                "is-bare-ip", indicator -> indicator.indicator().type() == IndicatorType.IPV4
                        && !indicator.classification().features().hasPort(),
                "is-address-with-detail", indicator -> indicator.classification().features().hasPath()
                        || indicator.classification().features().hasPort(),
                "is-clean-host", indicator -> indicator.indicator().type() == IndicatorType.DOMAIN
                        && !indicator.classification().features().hasPath()));

        assertThat(validators.isValid("bare-ip", "192.0.2.1")).isTrue();
        assertThat(validators.isValid("bare-ip", "192.0.2.1:8443")).isFalse();
        assertThat(validators.isValid("url-address", "example.org/drop.exe")).isTrue();
        assertThat(validators.isValid("clean-domain", "example.org")).isTrue();
        assertThat(validators.isValid("clean-domain", "example.org/drop.exe")).isFalse();
        assertThat(validators.isValid("hash", "A".repeat(32))).isTrue();
        assertThat(validators.isValid("hash", "prefix " + "A".repeat(32))).isFalse();
    }

    @Test
    void rejectsUnknownRulesAndMissingStructuralConditions() {
        CsvImportValueValidatorRegistry validators = validators(Map.of());

        assertThatThrownBy(() -> validators.isValid("unknown", "example.org"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown import value validation rule: unknown");
        assertThatThrownBy(() -> validators.isValid("url-address", "example.org/drop.exe"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Missing import validation condition: is-address-with-detail");
    }

    @ParameterizedTest
    @CsvSource({"md5,32", "sha1,40", "sha256,64"})
    void rejectsHashesPlacedInTheWrongAlgorithmColumn(String rule, int length) {
        CsvImportValueValidatorRegistry validators = validators(Map.of());

        for (int candidateLength : List.of(32, 40, 64)) {
            assertThat(validators.isValid(rule, "A".repeat(candidateLength)))
                    .as("%s column with %s hexadecimal characters", rule, candidateLength)
                    .isEqualTo(candidateLength == length);
        }
        assertThat(validators.isValid(rule, "prefix " + "A".repeat(length))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"192.0.2.1", "example.org", "https://example.org:8443/path?q=1"})
    void acceptsWholeNetworkAddressesWithoutRequiringHostCleanup(String value) {
        assertThat(validators(Map.of()).isValid("network-address", value)).isTrue();
    }

    @Test
    void networkAddressesRejectHashesAndUnrecognizedInput() {
        CsvImportValueValidatorRegistry validators = validators(Map.of());

        assertThat(validators.isValid("network-address", "A".repeat(32))).isFalse();
        assertThat(validators.isValid("network-address", "not an indicator")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://999.999.999.999/path", "192.000.2.1", "example..org",
            "https://example.org:99999/path", "https://user@example.org/path"})
    void rejectsMalformedNetworkSyntaxEvenWhenExtractionRecognizesTheCategory(String value) {
        CsvImportValueValidatorRegistry validators = validators(Map.of());

        for (String rule : List.of("network-address", "bare-ip", "url-address", "clean-domain")) {
            assertThat(validators.isValid(rule, value)).as("%s for %s", rule, value).isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "10", "-10", "9223372036854775807", "-9223372036854775808"})
    void acceptsCanonicalIntegersAcrossTheStorageRange(String value) {
        assertThat(validators(Map.of()).isValid("canonical-integer", value)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"010", "+10", "-0", "10.0", "1e1", " 10", "10 ", "", "text",
            "9223372036854775808", "-9223372036854775809"})
    void rejectsIntegerSpellingsThatWouldChangeDuringStorage(String value) {
        assertThat(validators(Map.of()).isValid("canonical-integer", value)).isFalse();
    }

    private CsvImportValueValidatorRegistry validators(
            Map<String, Predicate<ClassifiedIndicator>> conditions) {
        return new CsvImportValueValidatorRegistry(
                text -> new RefangOutcome(text, List.of()),
                this::extracted,
                new IndicatorClassifier(indicator -> classified(indicator.value(), indicator.type())),
                conditions);
    }

    private ExtractionOutcome extracted(String text) {
        String value = text.strip();
        IndicatorType type;
        if (value.matches("[A-F]{32}")) {
            type = IndicatorType.MD5;
        } else if (value.matches("[A-F]{40}")) {
            type = IndicatorType.SHA1;
        } else if (value.matches("[A-F]{64}")) {
            type = IndicatorType.SHA256;
        } else if (value.matches("[0-9.]+(?::[0-9]+)?")) {
            type = IndicatorType.IPV4;
        } else if (value.contains("/")) {
            type = IndicatorType.URL;
        } else if (value.matches("[a-z.]+")) {
            type = IndicatorType.DOMAIN;
        } else {
            return new ExtractionOutcome(List.of(), List.of());
        }
        return new ExtractionOutcome(
                List.of(new RawIndicator(value, type, text.indexOf(value))), List.of());
    }

    private ClassificationDecision classified(String value, IndicatorType type) {
        boolean port = value.matches(".*:[0-9]+$");
        boolean path = value.contains("/");
        HostKind kind = type == IndicatorType.IPV4 ? HostKind.IP : HostKind.REGISTRABLE;
        return new ClassificationDecision(
                new IndicatorFeatures(value, value, port, path, false, kind),
                0, List.of("test"), new MaskMatch("u:hAS", "h:dAS"));
    }
}
