package com.iocextractor.domain.refang;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class RefangDecisionTest {

    @ParameterizedTest(name = "index={0}, replacements={1}")
    @CsvSource({"-1, 1", "0, 0"})
    void rejects_a_non_applied_decision(int ruleIndex, int replacements) {
        RefangRule rule = new RefangRule("[.]", ".");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RefangDecision(ruleIndex, rule, replacements))
                .withMessage("Applied refang decision requires a valid index and count");
    }
}
