package com.iocextractor.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;
import org.springframework.validation.FieldError;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IocPropertiesBindingTest {

    @Test
    void defaultConfigurationBindsThroughBootValidation() {
        contextRunner().run(context -> {
            assertThat(context).hasSingleBean(IocProperties.class);
            assertThat(context).hasBean("configurationPropertiesValidator");
            assertThat(context.getBean(IocProperties.class).lookup()).isNull();
        });
    }

    @Test
    void reportsMultipleSemanticErrorsTogether() {
        contextRunner(
                "ioc.storage.dataframe.type=disabled",
                "ioc.lookup.deduplicate=false",
                "ioc.sync.retry.max-attempts=1",
                "ioc.sync.retry.backoff=10s",
                "ioc.sync.retry.max-backoff=1s",
                "ioc.sync.retry.multiplier=0.5")
                .run(context -> assertThat(fieldErrors(context.getStartupFailure()))
                        .extracting(FieldError::getField)
                        .contains(
                                "storage.dataframe.type",
                                "lookup.deduplicate",
                                "sync.retry.maxBackoff",
                                "sync.retry.multiplier"));
    }

    @Test
    void rejectsLegacyLookupStorageKeysWithFieldErrors() {
        contextRunner(
                "ioc.lookup.type=csv",
                "ioc.lookup.path=./dataframe/masks_list.csv",
                "ioc.lookup.artifacts[0].name=masks")
                .run(context -> assertThat(fieldErrors(context.getStartupFailure()))
                        .extracting(FieldError::getField)
                        .contains("lookup.type", "lookup.path", "lookup.artifacts"));
    }

    @Test
    void rejectsInvalidSyncReferencesAndProfileBeforeBeanGraph() {
        contextRunner(
                "ioc.sync.endpoints[0].name=known",
                "ioc.sync.endpoints[0].transport=smb",
                "ioc.sync.endpoints[0].smb.host=server",
                "ioc.sync.endpoints[0].smb.share=share",
                "ioc.sync.endpoints[0].smb.username=user",
                "ioc.sync.endpoints[0].smb.password=secret",
                "ioc.sync.fetch.sources[0].name=source",
                "ioc.sync.fetch.sources[0].endpoint=missing",
                "ioc.sync.fetch.sources[0].remote-path=/incoming",
                "ioc.sync.fetch.sources[0].include[0]=*.htm",
                "ioc.sync.fetch.sources[0].exclude[0]=*.part",
                "ioc.sync.publish.targets[0].name=target",
                "ioc.sync.publish.targets[0].endpoint=missing",
                "ioc.sync.publish.targets[0].remote-path=/out",
                "ioc.sync.publish.targets[0].export-profile=missing-profile")
                .run(context -> assertThat(fieldErrors(context.getStartupFailure()))
                        .extracting(FieldError::getField)
                        .contains(
                                "sync.fetch.sources[0].endpoint",
                                "sync.publish.targets[0].endpoint",
                                "sync.publish.targets[0].exportProfile"));
    }

    @Test
    void rejectsSyncTransportAndTimingSemanticsWithFieldErrors() {
        contextRunner(
                "ioc.sync.endpoints[0].name=share",
                "ioc.sync.endpoints[0].transport=smb",
                "ioc.sync.endpoints[0].smb.host=server",
                "ioc.sync.endpoints[0].smb.share=share",
                "ioc.sync.endpoints[0].smb.username=user",
                "ioc.sync.endpoints[0].smb.password=secret",
                "ioc.sync.endpoints[0].smb.read-timeout=45s",
                "ioc.sync.fetch.interval=0s",
                "ioc.sync.publish.interval=0s")
                .run(context -> assertThat(fieldErrors(context.getStartupFailure()))
                        .extracting(FieldError::getField)
                        .contains(
                                "sync.endpoints[0].smb.readTimeout",
                                "sync.fetch.interval",
                                "sync.publish.interval"));
    }

    @Test
    void missingNestedValuesDoNotFailWithConstructorNpe() {
        contextRunner("ioc.sync.fetch.sources[0].include[0]=*.htm")
                .run(context -> {
                    assertThat(rootCause(context.getStartupFailure()))
                            .isNotInstanceOf(NullPointerException.class);
                    assertThat(fieldErrors(context.getStartupFailure()))
                            .extracting(FieldError::getField)
                            .contains("name", "endpoint", "remotePath", "exclude");
                });
    }

    @Test
    void rejectsTypoInIdentityArtifactName() {
        contextRunner(concat(
                identity(0, "mask", "mask"),
                identity(1, "ip_list", "ip"),
                identity(2, "address_blacklist", "forbidden_url", "forbidden_ip"),
                identity(3, "hashes", "hash_md5", "hash_sha1", "hash_sha256")))
                .run(context -> assertThat(fieldErrors(context.getStartupFailure()))
                        .extracting(FieldError::getField)
                        .contains("artifactIdentity.artifacts[0].name"));
    }

    @Test
    void rejectsEnabledSinkArtifactWithoutIdentity() {
        contextRunner(concat(
                sinkArtifact(0, "masks", true, "id", "mask"),
                sinkArtifact(1, "custom_list", true, "value"),
                identity(0, "masks", "mask")))
                .run(context -> assertThat(fieldErrors(context.getStartupFailure()))
                        .extracting(FieldError::getField)
                        .contains("sink.artifacts[1].name"));
    }

    @Test
    void acceptsIdentityForDisabledButExistingArtifact() {
        contextRunner(concat(
                sinkArtifact(0, "legacy_list", false, "value"),
                identity(0, "legacy_list", "value")))
                .run(context -> assertThat(context).hasSingleBean(IocProperties.class));
    }

    @Test
    void rejectsDuplicateSinkArtifactNames() {
        contextRunner(concat(
                sinkArtifact(0, "masks", true, "id", "mask"),
                sinkArtifact(1, "masks", true, "id", "mask"),
                identity(0, "masks", "mask")))
                .run(context -> assertThat(fieldErrors(context.getStartupFailure()))
                        .extracting(FieldError::getField)
                        .contains("sink.artifacts[1].name"));
    }

    @Test
    void rejectsDuplicateIdentityArtifactNames() {
        contextRunner(concat(
                identity(0, "masks", "mask"),
                identity(1, "masks", "mask")))
                .run(context -> assertThat(fieldErrors(context.getStartupFailure()))
                        .extracting(FieldError::getField)
                        .contains("artifactIdentity.artifacts[1].name"));
    }

    @Test
    void rejectsDuplicateColumnNamesInsideOneArtifact() {
        contextRunner(concat(
                sinkArtifact(0, "masks", true, "mask", "mask"),
                identity(0, "masks", "mask")))
                .run(context -> assertThat(fieldErrors(context.getStartupFailure()))
                        .extracting(FieldError::getField)
                        .contains("sink.artifacts[0].columns[1].name"));
    }

    @Test
    void rejectsTypoInCompositeIdentityKeyColumn() {
        contextRunner(concat(
                identity(0, "masks", "missing_mask"),
                identity(1, "ip_list", "ip"),
                identity(2, "address_blacklist", "forbidden_url", "forbidden_ip"),
                identity(3, "hashes", "hash_md5", "hash_sha1", "hash_sha256")))
                .run(context -> assertThat(fieldErrors(context.getStartupFailure()))
                        .extracting(FieldError::getField)
                        .contains("artifactIdentity.artifacts[0].keyColumns[0]"));
    }

    @Test
    void rejectsNumericIdStartWithoutPublicIdColumn() {
        contextRunner(concat(
                sinkArtifact(0, "address_blacklist", true, "forbidden_url", "forbidden_ip"),
                new String[] { "ioc.sink.artifacts[0].id.start=42" },
                identity(0, "address_blacklist", "forbidden_url")))
                .run(context -> assertThat(fieldErrors(context.getStartupFailure()))
                        .extracting(FieldError::getField)
                        .contains("sink.artifacts[0].id.start"));
    }

    @Test
    void reportsMultipleIdentityAndSinkMistakesTogether() {
        contextRunner(concat(
                sinkArtifact(0, "masks", true, "mask", "mask"),
                sinkArtifact(1, "masks", true, "value"),
                identity(0, "missing", "ghost"),
                identity(1, "masks", "ghost")))
                .run(context -> assertThat(fieldErrors(context.getStartupFailure()))
                        .extracting(FieldError::getField)
                        .contains(
                                "sink.artifacts[0].columns[1].name",
                                "sink.artifacts[1].name",
                                "artifactIdentity.artifacts[0].name",
                                "artifactIdentity.artifacts[1].keyColumns[0]"));
    }

    private ApplicationContextRunner contextRunner(String... overrides) {
        return new ApplicationContextRunner()
                .withInitializer(IocPropertiesBindingTest::addDefaultApplicationYaml)
                .withUserConfiguration(TestConfig.class)
                .withPropertyValues(overrides);
    }

    private static List<FieldError> fieldErrors(Throwable failure) {
        BindValidationException validation = cause(failure, BindValidationException.class);
        assertThat(validation).isNotNull();
        return validation.getValidationErrors().getAllErrors().stream()
                .filter(FieldError.class::isInstance)
                .map(FieldError.class::cast)
                .toList();
    }

    private static <T extends Throwable> T cause(Throwable throwable, Class<T> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current != null && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static void addDefaultApplicationYaml(ConfigurableApplicationContext context) {
        try {
            MutablePropertySources sources = context.getEnvironment().getPropertySources();
            sources.addLast(new YamlPropertySourceLoader()
                    .load("defaults", new ClassPathResource("application.yml")).getFirst());
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot load default application.yml", ex);
        }
    }

    private static String[] sinkArtifact(int index, String name, boolean enabled, String... columns) {
        List<String> values = new ArrayList<>();
        String prefix = "ioc.sink.artifacts[%d]".formatted(index);
        values.add("%s.name=%s".formatted(prefix, name));
        values.add("%s.enabled=%s".formatted(prefix, enabled));
        values.add("%s.path=./dataframe/%s_generated.csv".formatted(prefix, name));
        values.add("%s.accepts[0]=IPV4".formatted(prefix));
        for (int i = 0; i < columns.length; i++) {
            values.add("%s.columns[%d].name=%s".formatted(prefix, i, columns[i]));
            values.add("%s.columns[%d].from=%s".formatted(prefix, i, "id".equals(columns[i]) ? "id" : "value"));
        }
        return values.toArray(String[]::new);
    }

    private static String[] identity(int index, String name, String... keyColumns) {
        List<String> values = new ArrayList<>();
        String prefix = "ioc.artifact-identity.artifacts[%d]".formatted(index);
        values.add("%s.name=%s".formatted(prefix, name));
        for (int i = 0; i < keyColumns.length; i++) {
            values.add("%s.key-columns[%d]=%s".formatted(prefix, i, keyColumns[i]));
        }
        return values.toArray(String[]::new);
    }

    private static String[] concat(String[]... groups) {
        List<String> values = new ArrayList<>();
        for (String[] group : groups) {
            values.addAll(List.of(group));
        }
        return values.toArray(String[]::new);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(IocProperties.class)
    @Import(ConfigPreflightConfiguration.class)
    static class TestConfig {
    }
}
