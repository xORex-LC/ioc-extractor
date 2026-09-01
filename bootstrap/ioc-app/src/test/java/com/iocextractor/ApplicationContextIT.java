package com.iocextractor;

import com.iocextractor.application.tck.junit.IntegrationTest;
import com.iocextractor.application.port.in.ExtractIocsUseCase;
import com.iocextractor.bootstrap.IocProperties;
import com.iocextractor.bootstrap.StorageType;
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
 * default configuration. Isolated from project artifacts by redirecting service
 * and dataframe SQLite storage to temporary target files.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.main.banner-mode=off"
})
@IntegrationTest
class ApplicationContextIT {

    private static final Path SERVICE_DB = Path.of(
            "target", "lazy-service-" + UUID.randomUUID() + ".db");
    private static final Path DATAFRAME_DB = Path.of(
            "target", "context-dataframe-" + UUID.randomUUID() + ".db");

    @DynamicPropertySource
    static void storagePaths(DynamicPropertyRegistry registry) {
        registry.add("ioc.storage.service.url", () -> "jdbc:sqlite:" + SERVICE_DB);
        registry.add("ioc.storage.dataframe.url", () -> "jdbc:sqlite:" + DATAFRAME_DB);
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
        assertThat(props.storage().service().type()).isEqualTo(StorageType.JDBC);
        assertThat(props.storage().service().url()).isEqualTo("jdbc:sqlite:" + SERVICE_DB);
        assertThat(props.storage().dataframe().type()).isEqualTo(StorageType.JDBC);
        assertThat(props.storage().dataframe().url()).isEqualTo("jdbc:sqlite:" + DATAFRAME_DB);
        assertThat(props.storage().service().sqlite().tuning()).isEqualTo("low-memory");
        assertThat(props.storage().service().pool().writeMax()).isEqualTo(1);
        assertThat(props.storage().service().pool().readMax()).isEqualTo(2);
        var beanFactory = ((ConfigurableApplicationContext) context).getBeanFactory();
        // The holder may exist, but neither service migration nor the export graph may
        // resolve it while the ordinary oneshot context and root help are initialized.
        assertThat(beanFactory.containsSingleton("serviceSchemaMigration")).isFalse();
        assertThat(beanFactory.containsSingleton("exportArtifactsUseCase")).isFalse();
        assertThat(SERVICE_DB).doesNotExist();
    }
}
