package com.iocextractor.application.artifact;

import org.junit.jupiter.api.Test;

import java.security.MessageDigest;

import static org.assertj.core.api.Assertions.assertThat;

class FingerprintFramingTest {

    @Test
    void requiredValuesAreLengthFramedRatherThanConcatenated() throws Exception {
        MessageDigest first = MessageDigest.getInstance("SHA-256");
        FingerprintFraming.add(first, "ab");
        FingerprintFraming.add(first, "c");
        MessageDigest second = MessageDigest.getInstance("SHA-256");
        FingerprintFraming.add(second, "a");
        FingerprintFraming.add(second, "bc");

        assertThat(first.digest()).isNotEqualTo(second.digest());
    }

    @Test
    void nullableFramingDistinguishesNullFromEmptyText() throws Exception {
        MessageDigest absent = MessageDigest.getInstance("SHA-256");
        FingerprintFraming.addNullable(absent, null);
        MessageDigest empty = MessageDigest.getInstance("SHA-256");
        FingerprintFraming.addNullable(empty, "");

        assertThat(absent.digest()).isNotEqualTo(empty.digest());
    }
}
