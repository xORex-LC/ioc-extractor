package com.iocextractor.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class DaemonWebEnvironmentPostProcessorTest {

    private final DaemonWebEnvironmentPostProcessor processor = new DaemonWebEnvironmentPostProcessor();

    @Test
    void enables_servlet_web_in_daemon_mode() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("ioc.runtime.mode", "daemon");

        processor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("spring.main.web-application-type")).isEqualTo("servlet");
        assertThat(environment.getProperty("spring.main.lazy-initialization")).isEqualTo("false");
        assertThat(environment.getProperty("spring.autoconfigure.exclude")).isNull();
    }

    @Test
    void leaves_oneshot_non_web() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("ioc.runtime.mode", "oneshot");

        processor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("spring.main.web-application-type")).isNull();
        assertThat(environment.getProperty("spring.main.lazy-initialization")).isEqualTo("true");
        assertThat(environment.getProperty("spring.autoconfigure.exclude")).isNull();
    }

    @Test
    void defaults_to_non_web_when_mode_absent() {
        MockEnvironment environment = new MockEnvironment();

        processor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("spring.main.web-application-type")).isNull();
        assertThat(environment.getProperty("spring.main.lazy-initialization")).isEqualTo("true");
        assertThat(environment.getProperty("spring.autoconfigure.exclude")).isNull();
    }
}
