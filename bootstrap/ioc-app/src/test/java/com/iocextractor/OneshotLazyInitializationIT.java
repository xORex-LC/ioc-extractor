package com.iocextractor;

import com.iocextractor.application.tck.junit.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies that parsing the oneshot command graph does not initialize JDBC storage. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "ioc.runtime.mode=oneshot",
        "spring.main.banner-mode=off"
})
@IntegrationTest
class OneshotLazyInitializationIT {

    private static final Path DATAFRAME_DB = Path.of(
            "target", "lazy-dataframe-" + UUID.randomUUID() + ".db");

    @DynamicPropertySource
    static void dataframePath(DynamicPropertyRegistry registry) {
        registry.add("ioc.storage.dataframe.url", () -> "jdbc:sqlite:" + DATAFRAME_DB);
    }

    @Test
    void rootCommandDoesNotResolveDataframeStorage(ConfigurableApplicationContext context) {
        ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();

        assertThat(beanFactory.containsSingleton("dataframeStorageDataSource")).isFalse();
        assertThat(beanFactory.containsSingleton("dataframeFormatSchemaMigration")).isFalse();
        assertThat(beanFactory.containsSingleton("extractIocsUseCase")).isFalse();
        assertThat(DATAFRAME_DB).doesNotExist();
    }
}
