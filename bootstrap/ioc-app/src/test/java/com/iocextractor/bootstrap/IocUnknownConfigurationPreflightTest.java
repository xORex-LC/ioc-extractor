package com.iocextractor.bootstrap;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class IocUnknownConfigurationPreflightTest {

    @Test
    void treatsNonContainerLeafTypesAsTerminalProperties() {
        assertThat(IocUnknownConfigurationPreflight.canTerminateAt(Path.class)).isTrue();
        assertThat(IocUnknownConfigurationPreflight.canTerminateAt(Charset.class)).isTrue();
        assertThat(IocUnknownConfigurationPreflight.canTerminateAt(Float.class)).isTrue();
        assertThat(IocUnknownConfigurationPreflight.canTerminateAt(Duration.class)).isTrue();
        assertThat(IocUnknownConfigurationPreflight.canTerminateAt(IdStart.class)).isTrue();
    }
}
