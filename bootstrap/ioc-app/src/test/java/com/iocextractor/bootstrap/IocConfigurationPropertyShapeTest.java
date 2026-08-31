package com.iocextractor.bootstrap;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IocConfigurationPropertyShapeTest {

    @Test
    void tokenizesCanonicalAndEnvironmentIndexesToTheSameShape() {
        assertThat(IocConfigurationPropertyShape.tokens("ioc.sync.endpoints[12].smb.host"))
                .containsExactly("ioc", "sync", "endpoints", "[12]", "smb", "host");
        assertThat(IocConfigurationPropertyShape.environmentTokens("ioc.sync.endpoints.12.smb.host"))
                .containsExactly("ioc", "sync", "endpoints", "[12]", "smb", "host");
    }

    @Test
    void rejectsMalformedOrNonNumericListIndexes() {
        assertThat(IocConfigurationPropertyShape.tokens("ioc.sync.endpoints[12.smb.host")).isEmpty();
        assertThat(IocConfigurationPropertyShape.isIndex("[12]")).isTrue();
        assertThat(IocConfigurationPropertyShape.isIndex("[source]")).isFalse();
        assertThat(IocConfigurationPropertyShape.isIndex("[]")).isFalse();
    }

    @Test
    void derivesCanonicalKebabCaseFromRecordComponentNames() {
        assertThat(IocConfigurationPropertyShape.kebabCase("requestTimeout"))
                .isEqualTo("request-timeout");
    }
}
