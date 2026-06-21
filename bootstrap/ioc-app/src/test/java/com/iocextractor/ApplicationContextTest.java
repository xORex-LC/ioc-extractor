package com.iocextractor;

import com.iocextractor.application.port.in.ExtractIocsUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test: the Spring context boots and wires the core use case from the
 * default configuration. Isolated from project artifacts — the lookup path is
 * overridden to a non-existent file (handled as empty storage), so the test
 * does not depend on {@code dataframe/} contents.
 */
@SpringBootTest(properties = {
        "ioc.lookup.path=target/test-no-such-lookup.csv",
        "spring.main.banner-mode=off"
})
class ApplicationContextTest {

    @Autowired
    ExtractIocsUseCase useCase;

    @Test
    void context_loads_and_wires_the_use_case() {
        assertThat(useCase).isNotNull();
    }
}
