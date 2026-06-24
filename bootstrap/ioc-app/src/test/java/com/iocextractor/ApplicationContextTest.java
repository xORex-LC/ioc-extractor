package com.iocextractor;

import com.iocextractor.application.port.in.ExtractIocsUseCase;
import com.iocextractor.bootstrap.IocProperties;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test: the Spring context boots and wires the core use case from the
 * default configuration. Isolated from project artifacts — the lookup path is
 * overridden to a non-existent file (handled as empty storage), so the test
 * does not depend on {@code dataframe/} contents.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "ioc.lookup.path=target/test-no-such-lookup.csv",
        "ioc.storage.dataframe.type=disabled",
        "spring.main.banner-mode=off"
})
class ApplicationContextTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    ExtractIocsUseCase useCase;

    @Autowired
    IocProperties props;

    @Test
    void context_loads_and_wires_the_use_case() {
        assertThat(useCase).isNotNull();
    }

    @Test
    void binds_service_storage_defaults_without_creating_storage_runtime() {
        assertThat(props.storage().service().type()).isEqualTo("jdbc");
        assertThat(props.storage().service().url()).isEqualTo("jdbc:sqlite:./var/ioc-service.db");
        assertThat(props.storage().service().sqlite().tuning()).isEqualTo("low-memory");
        assertThat(props.storage().service().pool().writeMax()).isEqualTo(1);
        assertThat(props.storage().service().pool().readMax()).isEqualTo(2);
        assertThat(context.getBeansOfType(HikariDataSource.class)).isEmpty();
    }
}
