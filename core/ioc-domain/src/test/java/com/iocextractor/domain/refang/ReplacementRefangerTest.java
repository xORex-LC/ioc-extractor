package com.iocextractor.domain.refang;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class ReplacementRefangerTest {

    private final Refanger refanger = new ReplacementRefanger(List.of(
            new RefangRule("hxxps", "https"),
            new RefangRule("hxxp", "http"),
            new RefangRule("[.]", "."),
            new RefangRule("[:]", ":")));

    @Test
    void refangs_defanged_url() {
        assertThat(refanger.refang("hxxps[:]//api[.]telegram[.]org/bot123"))
                .satisfies(outcome -> {
                    assertThat(outcome.text()).isEqualTo("https://api.telegram.org/bot123");
                    assertThat(outcome.decisions())
                            .extracting(RefangDecision::ruleIndex, RefangDecision::replacements)
                            .containsExactly(tuple(0, 1), tuple(2, 2), tuple(3, 1));
                });
    }

    @Test
    void refangs_defanged_ipv4() {
        assertThat(refanger.refang("84[.]38[.]129[.]122").text()).isEqualTo("84.38.129.122");
    }

    @Test
    void order_matters_hxxps_before_hxxp() {
        assertThat(refanger.refang("hxxps://x").text()).isEqualTo("https://x");
    }

    @Test
    void leaves_clean_text_untouched() {
        assertThat(refanger.refang("voffice.top")).satisfies(outcome -> {
            assertThat(outcome.text()).isEqualTo("voffice.top");
            assertThat(outcome.decisions()).isEmpty();
        });
    }

    @Test
    void empty_text_is_an_explicit_noop() {
        assertThat(refanger.refang(""))
                .isEqualTo(new RefangOutcome("", List.of()));
    }
}
