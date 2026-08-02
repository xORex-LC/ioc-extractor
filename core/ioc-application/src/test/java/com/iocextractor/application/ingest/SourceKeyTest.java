package com.iocextractor.application.ingest;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class SourceKeyTest {

    @Test
    void normalizesMachineIdentityIndependentlyOfDefaultLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            assertThat(new SourceKey("INGEST-ID").value()).isEqualTo("ingest-id");
        } finally {
            Locale.setDefault(previous);
        }
    }
}
