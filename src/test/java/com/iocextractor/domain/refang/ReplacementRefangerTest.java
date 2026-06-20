package com.iocextractor.domain.refang;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReplacementRefangerTest {

    private final Refanger refanger = new ReplacementRefanger(List.of(
            new RefangRule("hxxps", "https"),
            new RefangRule("hxxp", "http"),
            new RefangRule("[.]", "."),
            new RefangRule("[:]", ":")));

    @Test
    void refangs_defanged_url() {
        assertThat(refanger.refang("hxxps[:]//api[.]telegram[.]org/bot123"))
                .isEqualTo("https://api.telegram.org/bot123");
    }

    @Test
    void refangs_defanged_ipv4() {
        assertThat(refanger.refang("84[.]38[.]129[.]122")).isEqualTo("84.38.129.122");
    }

    @Test
    void order_matters_hxxps_before_hxxp() {
        assertThat(refanger.refang("hxxps://x")).isEqualTo("https://x");
    }

    @Test
    void leaves_clean_text_untouched() {
        assertThat(refanger.refang("voffice.top")).isEqualTo("voffice.top");
    }
}
