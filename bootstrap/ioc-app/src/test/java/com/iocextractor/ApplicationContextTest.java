package com.iocextractor;

import com.iocextractor.application.port.in.ExtractIocsUseCase;
import com.iocextractor.bootstrap.IocProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.UUID;

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

    private static final Path SERVICE_DB = Path.of(
            "target", "lazy-service-" + UUID.randomUUID() + ".db");

    @DynamicPropertySource
    static void servicePath(DynamicPropertyRegistry registry) {
        registry.add("ioc.storage.service.url", () -> "jdbc:sqlite:" + SERVICE_DB);
    }

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
        assertThat(props.storage().service().url()).isEqualTo("jdbc:sqlite:" + SERVICE_DB);
        assertThat(props.storage().service().sqlite().tuning()).isEqualTo("low-memory");
        assertThat(props.storage().service().pool().writeMax()).isEqualTo(1);
        assertThat(props.storage().service().pool().readMax()).isEqualTo(2);
        var beanFactory = ((ConfigurableApplicationContext) context).getBeanFactory();
        // The holder may exist, but neither migration nor the export graph may resolve
        // it while the ordinary oneshot context and root help are initialized.
        assertThat(beanFactory.containsSingleton("serviceSchemaMigration")).isFalse();
        assertThat(beanFactory.containsSingleton("exportArtifactsUseCase")).isFalse();
        assertThat(SERVICE_DB).doesNotExist();
    }
}
