package com.iocextractor.adapter.in.csv;

import com.iocextractor.application.classification.IndicatorClassifier;
import com.iocextractor.domain.classify.ClassificationDecision;
import com.iocextractor.domain.extract.ExtractionOutcome;
import com.iocextractor.domain.extract.RawIndicator;
import com.iocextractor.domain.feature.HostKind;
import com.iocextractor.domain.feature.IndicatorFeatures;
import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.domain.model.MaskMatch;
import com.iocextractor.domain.refang.RefangOutcome;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CsvImportValueValidatorRegistryTest {

    @Test
    void validatesWholeCellCarrierKindsThroughSharedStructuralConditions() {
        CsvImportValueValidatorRegistry validators = new CsvImportValueValidatorRegistry(
                text -> new RefangOutcome(text, List.of()),
                text -> extracted(text),
                new IndicatorClassifier(indicator -> classified(indicator.value(), indicator.type())),
                Map.of(
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

    private ExtractionOutcome extracted(String text) {
        String value = text.strip();
        IndicatorType type;
        if (value.matches("[A-F]{32}")) {
            type = IndicatorType.MD5;
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
