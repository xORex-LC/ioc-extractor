package com.iocextractor.processing.mapping;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class LowerHostTransformTest {
    private final LowerHostTransform transform = new LowerHostTransform();

    @Test
    void lowercasesOnlySchemeAndAuthorityAndPreservesCaseSensitiveSuffixes() {
        assertThat(transform.apply("HTTPS://EXAMPLE.COM:443/Case?Token=AbC#FragMent", null))
                .isEqualTo("https://example.com:443/Case?Token=AbC#FragMent");
        assertThat(transform.apply("EXAMPLE.COM#FragMent", null))
                .isEqualTo("example.com#FragMent");
        assertThat(transform.apply("EXAMPLE.COM?Token=AbC", null))
                .isEqualTo("example.com?Token=AbC");
    }
}
