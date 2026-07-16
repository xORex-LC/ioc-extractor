package com.iocextractor.bootstrap;

import com.iocextractor.domain.model.IndicatorType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.UnboundConfigurationPropertiesException;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.validation.FieldError;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IocPropertiesBindingTest {

    @Test
    void defaultConfigurationBindsThroughBootValidation() {
        contextRunner().run(context -> {
            assertThat(context).hasSingleBean(IocProperties.class);
            assertThat(context).hasBean("configurationPropertiesValidator");
        });
    }

    @Test
    void reportsMultipleSemanticErrorsTogether() {
        contextRunner(
                "ioc.sync.retry.max-attempts=1",
                "ioc.sync.retry.backoff=10s",
                "ioc.sync.retry.max-backoff=1s",
                "ioc.sync.retry.multiplier=0.5")
                .run(context -> assertThat(fieldErrors(context.getStartupFailure()))
                        .extracting(FieldError::getField)
                        .contains(
                                "sync.retry.maxBackoff",
                                "sync.retry.multiplier"));
    }

    @Test
    void bindsClosedSelectorVariants() {
        contextRunner(
                "ioc.engine=Re2J",
                "ioc.runtime.mode=DAEMON",
                "ioc.observability.mode=DAEMON",
                "ioc.storage.service.type=JDBC",
                "ioc.storage.dataframe.type=jdbc",
                "ioc.export.trigger.type=quiet-period",
                "ioc.ingestion.ledger.type=FILE")
                .run(context -> {
                    IocProperties props = context.getBean(IocProperties.class);
                    assertThat(props.engine()).isEqualTo(EngineType.RE2J);
                    assertThat(props.runtime().mode()).isEqualTo(RuntimeMode.DAEMON);
                    assertThat(props.observability().mode()).isEqualTo(ObservabilityMode.DAEMON);
                    assertThat(props.storage().service().type()).isEqualTo(StorageType.JDBC);
                    assertThat(ArtifactIdStrategy.parse("DESCENDING")).isEqualTo(ArtifactIdStrategy.DESCENDING);
                    assertThat(props.artifactIdentity().artifacts().get(2).keyMode())
                            .isEqualTo(ArtifactKeyMode.FIRST_NON_EMPTY);
                    assertThat(ArtifactKeyMode.parse("first_non_empty")).isEqualTo(ArtifactKeyMode.FIRST_NON_EMPTY);
                    assertThat(props.export().trigger().type()).isEqualTo(ExportTriggerType.QUIET_PERIOD);
                    assertThat(ExportOutputMode.parse("Complete")).isEqualTo(ExportOutputMode.COMPLETE);
                    assertThat(props.ingestion().ledger().type()).isEqualTo(IngestionLedgerType.FILE);
                    assertThat(RetentionActionType.parse("ARCHIVE")).isEqualTo(RetentionActionType.ARCHIVE);
                });
    }

    @Test
    void rejectsInvalidClosedSelectorWithSupportedValues() {
        contextRunner("ioc.engine=regex")
                .run(context -> assertThat(causeMessages(context.getStartupFailure()))
                        .contains("ioc.engine")
                        .contains("re2j, jdk"));
    }

    @Test
    void rejectsSyncTransportTypoDuringBinding() {
        contextRunner(
                "ioc.sync.endpoints[0].name=share",
                "ioc.sync.endpoints[0].transport=ftp")
                .run(context -> assertThat(causeMessages(context.getStartupFailure()))
                        .contains("ioc.sync.endpoints[].transport")
                        .contains("smb"));
    }

    @Test
    void rejectsRetentionActionTypoDuringBinding() {
        contextRunner("ioc.maintenance.retention.targets[0].action=drop")
                .run(context -> assertThat(causeMessages(context.getStartupFailure()))
                        .contains("ioc.maintenance.retention.targets[].action")
                        .contains("delete, archive"));
    }

    @Test
    void reportsRetryMaxAttemptsThroughPreflightOnly() {
        contextRunner("ioc.sync.retry.max-attempts=0")
                .run(context -> assertThat(fieldErrors(context.getStartupFailure()))
                        .filteredOn(error -> "sync.retry.maxAttempts".equals(error.getField()))
                        .singleElement()
                        .satisfies(error -> assertThat(error.getDefaultMessage())
                                .contains("set it to at least 1")));
    }

    @Test
    void rejectsUnknownIocKeyFromDefaultStyleOverrides() {
        contextRunner("ioc.pipeline.deduplicat=false")
                .run(context -> assertThat(unboundKeys(context.getStartupFailure()))
                        .containsExactly("ioc.pipeline.deduplicat"));
    }

    @Test
    void reportsUnknownKeysBeforeSemanticValidationErrors() {
        contextRunner(
                "ioc.pipeline.deduplicat=false",
                "ioc.sync.publish.targets[0].name=target",
                "ioc.sync.publish.targets[0].endpoint=missing",
                "ioc.sync.publish.targets[0].remote-path=/out",
                "ioc.sync.publish.targets[0].export-profile=missing-profile")
                .run(context -> {
                    assertThat(unboundKeys(context.getStartupFailure()))
                            .containsExactly("ioc.pipeline.deduplicat");
                    assertThat(cause(context.getStartupFailure(), BindValidationException.class)).isNull();
                });
    }

    @Test
    void rejectsRemovedLegacyLookupKeyAsUnknown() {
        contextRunner("ioc.lookup.deduplicate=false")
                .run(context -> assertThat(unboundKeys(context.getStartupFailure()))
                        .containsExactly("ioc.lookup.deduplicate"));
    }

    @Test
    void rejectsRemovedLegacySmbReadTimeoutKeyAsUnknown() {
        contextRunner(
                "ioc.sync.endpoints[0].name=share",
                "ioc.sync.endpoints[0].transport=smb",
                "ioc.sync.endpoints[0].smb.host=server",
                "ioc.sync.endpoints[0].smb.share=share",
                "ioc.sync.endpoints[0].smb.username=user",
                "ioc.sync.endpoints[0].smb.password=secret",
                "ioc.sync.endpoints[0].smb.read-timeout=45s")
                .run(context -> assertThat(unboundKeys(context.getStartupFailure()))
                        .containsExactly("ioc.sync.endpoints[0].smb.read-timeout"));
    }

    @Test
    void rejectsUnknownNestedRecordKey() {
        contextRunner(
                "ioc.sync.endpoints[0].name=share",
                "ioc.sync.endpoints[0].transport=smb",
                "ioc.sync.endpoints[0].smb.host=server",
                "ioc.sync.endpoints[0].smb.share=share",
                "ioc.sync.endpoints[0].smb.username=user",
                "ioc.sync.endpoints[0].smb.password=secret",
                "ioc.sync.endpoints[0].smb.unknown-timeout=45s")
                .run(context -> assertThat(unboundKeys(context.getStartupFailure()))
                        .containsExactly("ioc.sync.endpoints[0].smb.unknown-timeout"));
    }

    @Test
    void rejectsUnknownIocKeyFromOptionalOverlay(@TempDir Path tempDir) throws IOException {
        Path overlay = tempDir.resolve("application.yml");
        Files.writeString(overlay, """
                ioc:
                  pipeline:
                    deduplicat: false
                """);

        contextRunnerWithYamlOverlay(overlay)
                .run(context -> assertThat(unboundKeys(context.getStartupFailure()))
                        .containsExactly("ioc.pipeline.deduplicat"));
    }

    @Test
    void rejectsUnknownIocKeyFromCliOverride() {
        SpringApplication app = springApplication();

        assertThatThrownBy(() -> app.run("--ioc.pipeline.deduplicat=false"))
                .satisfies(failure -> assertThat(unboundKeys(failure))
                        .containsExactly("ioc.pipeline.deduplicat"));
    }

    @Test
    void runsRegistryPreflightAtStartupWithLazyInitialization() {
        SpringApplication app = springApplication();

        assertThatThrownBy(() -> app.run(
                "--spring.main.lazy-initialization=true",
                "--ioc.classify.rules[0].when[0]=has-secret-sauce",
                "--ioc.classify.rules[0].url-match=u:hAS,pEX",
                "--ioc.classify.rules[0].host-match="))
                .satisfies(failure -> assertThat(causeMessages(failure))
                        .contains(
                                "CONFIG.REGISTRY",
                                "ioc.classify.rules[0].when[0]",
                                "has-secret-sauce"));
    }

    @Test
    void acceptsKnownCliOverride() {
        SpringApplication app = springApplication();

        try (ConfigurableApplicationContext context = app.run("--ioc.pipeline.deduplicate=false")) {
            assertThat(context.getBean(IocProperties.class).pipeline().deduplicate()).isFalse();
        }
    }

    @Test
    void bindsTypedPipelineFailurePolicyAndDiagnosticBudget() {
        SpringApplication app = springApplication();

        try (ConfigurableApplicationContext context = app.run(
                "--ioc.pipeline.failure-policy=collect-and-continue",
                "--ioc.pipeline.max-diagnostics-per-run=42")) {
            assertThat(context.getBean(IocProperties.class).pipeline().failurePolicy())
                    .isEqualTo(PipelineFailurePolicy.COLLECT_AND_CONTINUE);
            assertThat(context.getBean(IocProperties.class).pipeline().maxDiagnosticsPerRun()).isEqualTo(42);
        }
    }

    @Test
    void rejectsUnknownSystemEnvironmentKeyButIgnoresNonIocSystemProperties() {
        contextRunnerWithSystemSources().run(context -> assertThat(unboundKeys(context.getStartupFailure()))
                .containsExactly("ioc.unrelated.operator.flag"));
    }

    @Test
    void acceptsKnownEnvironmentOverride() {
        contextRunnerWithEnvironment(Map.of("IOC_PIPELINE_DEDUPLICATE", "false"))
                .run(context -> assertThat(context.getBean(IocProperties.class).pipeline().deduplicate()).isFalse());
    }

    @Test
    void ignoresBareIocEnvironmentNames() {
        contextRunnerWithEnvironment(Map.of("IOC", "true", "IOC_", "true"))
                .run(context -> assertThat(context).hasSingleBean(IocProperties.class));
    }

    @Test
    void acceptsMultiwordEnvironmentProperty() {
        contextRunnerWithEnvironment(Map.of("IOC_INGESTION_STABILITY_QUIET_PERIOD", "15s"))
                .run(context -> assertThat(context.getBean(IocProperties.class).ingestion().stability().quietPeriod())
                        .isEqualTo(java.time.Duration.ofSeconds(15)));
    }

    @Test
    void acceptsMapTailEnvironmentProperty() {
        contextRunnerWithEnvironment(Map.of("IOC_PATTERNS_SHA256", "env-sha256-pattern"))
                .run(context -> assertThat(context.getBean(IocProperties.class).patterns().get(IndicatorType.SHA256))
                        .isEqualTo("env-sha256-pattern"));
    }

    @Test
    void acceptsEnvironmentValuesUsingCustomConverters() {
        contextRunnerWithEnvironment(Map.of("IOC_ENGINE", "Re2J"))
                .run(context -> {
                    IocProperties properties = context.getBean(IocProperties.class);
                    assertThat(properties.engine()).isEqualTo(EngineType.RE2J);
                });
        assertThat(new IocEnvironmentPropertyMatcher().match("IOC_SINK_ARTIFACTS_0_ID_START").isKnown()).isTrue();
    }

    @Test
    void detectsAmbiguousEnvironmentSchemaSegmentation() {
        IocEnvironmentPropertyMatcher.MatchResult result = new IocEnvironmentPropertyMatcher(AmbiguousRoot.class)
                .match("IOC_FOO_BAR");

        assertThat(result.canonicalNames()).containsExactlyInAnyOrder("ioc.foo-bar", "ioc.foo.bar");
        assertThat(result.isAmbiguous()).isTrue();
    }

    @Test
    void acceptsCompleteIndexedEnvironmentRecord() {
        contextRunnerWithEnvironment(Map.of(
                "IOC_CLASSIFY_RULES_0_WHEN_0", "has-query",
                "IOC_CLASSIFY_RULES_0_URL_MATCH", "u:hAS,pEX",
                "IOC_CLASSIFY_RULES_0_HOST_MATCH", ""))
                .run(context -> assertThat(context.getBean(IocProperties.class).classify().rules().getFirst().when())
                        .containsExactly("has-query"));
    }

    @Test
    void preservesBindingFailureForInvalidKnownEnvironmentValue() {
        contextRunnerWithEnvironment(Map.of("IOC_ENGINE", "regex"))
                .run(context -> {
                    assertThat(unboundKeysOrEmpty(context.getStartupFailure())).isEmpty();
                    assertThat(causeMessages(context.getStartupFailure())).contains("ioc.engine");
                });
    }

    @Test
    void rejectsRemovedLegacyLookupEnvironmentKeyAsUnknown() {
        contextRunnerWithEnvironment(Map.of("IOC_LOOKUP_DEDUPLICATE", "false"))
                .run(context -> assertThat(unboundKeys(context.getStartupFailure()))
                        .containsExactly("ioc.lookup.deduplicate"));
    }

    @Test
    void rejectsRemovedLegacySmbReadTimeoutEnvironmentKeyAsUnknown() {
        contextRunnerWithEnvironment(Map.of("IOC_SYNC_ENDPOINTS_0_SMB_READ_TIMEOUT", "45s"))
                .run(context -> assertThat(unboundKeys(context.getStartupFailure()))
                        .containsExactly("ioc.sync.endpoints[0].smb.read.timeout"));
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
                "ioc.sync.fetch.interval=0s",
                "ioc.sync.publish.interval=0s")
                .run(context -> assertThat(fieldErrors(context.getStartupFailure()))
                        .extracting(FieldError::getField)
                        .contains(
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
                            .contains(
                                    "sync.fetch.sources[0].name",
                                    "sync.fetch.sources[0].endpoint",
                                    "sync.fetch.sources[0].remotePath",
                                    "sync.fetch.sources[0].exclude");
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
    void bindsIdStartAutoAndExplicitValues() {
        contextRunner(artifactWithIdStart("AUTO"))
                .run(context -> {
                    IocProperties.Sink.Artifact.Id id = context.getBean(IocProperties.class)
                            .sink().artifacts().getFirst().id();
                    assertThat(id.start()).isInstanceOf(IdStart.Auto.class);
                    assertThat(IdStart.parse(" auto ")).isInstanceOf(IdStart.Auto.class);
                });

        contextRunner(artifactWithIdStart("42"))
                .run(context -> {
                    IocProperties.Sink.Artifact.Id id = context.getBean(IocProperties.class)
                            .sink().artifacts().getFirst().id();
                    assertThat(id.start()).isInstanceOfSatisfying(IdStart.Explicit.class,
                            explicit -> assertThat(explicit.value()).isEqualTo(42L));
                });
    }

    @Test
    void rejectsInvalidIdStartValuesDuringBinding() {
        for (String invalid : List.of("10O0", "\\u0430uto", "", "9223372036854775808")) {
            String value = "\\u0430uto".equals(invalid) ? "\u0430uto" : invalid;
            contextRunner(artifactWithIdStart(value))
                    .run(context -> assertThat(causeMessages(context.getStartupFailure()))
                            .contains("ioc.sink.artifacts[].id.start")
                            .contains("auto")
                            .contains("signed 64-bit"));
        }
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
        // TestPropertyValues merges indexed lists over application.yml; it cannot shorten default YAML lists.
        return new ApplicationContextRunner()
                .withInitializer(IocPropertiesBindingTest::addDefaultApplicationYaml)
                .withUserConfiguration(TestConfig.class)
                .withPropertyValues(overrides);
    }

    private ApplicationContextRunner contextRunnerWithYamlOverlay(Path overlay) {
        return new ApplicationContextRunner()
                .withInitializer(context -> addYaml(context, "overlay", new FileSystemResource(overlay), true))
                .withInitializer(IocPropertiesBindingTest::addDefaultApplicationYaml)
                .withUserConfiguration(TestConfig.class);
    }

    private ApplicationContextRunner contextRunnerWithSystemSources() {
        return contextRunnerWithEnvironment(Map.of(
                "IOC_UNRELATED_OPERATOR_FLAG", "true",
                "UNRELATED_SYSTEM_KEY", "x"))
                .withSystemProperties("IOC_PIPELINE_DEDUPLICAT=false", "random.system.key=value");
    }

    private ApplicationContextRunner contextRunnerWithEnvironment(Map<String, Object> environment) {
        return new ApplicationContextRunner()
                .withInitializer(context -> {
                    context.getEnvironment().getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                            "testEnv", environment));
                    addDefaultApplicationYaml(context);
                })
                .withUserConfiguration(TestConfig.class);
    }

    private static SpringApplication springApplication() {
        SpringApplication app = new SpringApplication(TestConfig.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(IocPropertiesBindingTest::addDefaultApplicationYaml);
        return app;
    }

    private static List<FieldError> fieldErrors(Throwable failure) {
        BindValidationException validation = cause(failure, BindValidationException.class);
        assertThat(validation).isNotNull();
        return validation.getValidationErrors().getAllErrors().stream()
                .filter(FieldError.class::isInstance)
                .map(FieldError.class::cast)
                .toList();
    }

    private static Set<String> unboundKeys(Throwable failure) {
        UnboundConfigurationPropertiesException unbound =
                cause(failure, UnboundConfigurationPropertiesException.class);
        assertThat(unbound).isNotNull();
        return unbound.getUnboundProperties().stream()
                .map(property -> property.getName().toString())
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private static Set<String> unboundKeysOrEmpty(Throwable failure) {
        UnboundConfigurationPropertiesException unbound = cause(failure, UnboundConfigurationPropertiesException.class);
        if (unbound == null) {
            return Set.of();
        }
        return unbound.getUnboundProperties().stream()
                .map(property -> property.getName().toString())
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
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

    private static String causeMessages(Throwable throwable) {
        List<String> messages = new ArrayList<>();
        Throwable current = throwable;
        while (current != null) {
            if (current.getMessage() != null) {
                messages.add(current.getMessage());
            }
            current = current.getCause();
        }
        return String.join("\n", messages);
    }

    private static void addDefaultApplicationYaml(ConfigurableApplicationContext context) {
        addYaml(context, "defaults", new ClassPathResource("application.yml"), false);
    }

    private static void addYaml(ConfigurableApplicationContext context,
                                String name,
                                Resource resource,
                                boolean first) {
        try {
            MutablePropertySources sources = context.getEnvironment().getPropertySources();
            var source = new YamlPropertySourceLoader().load(name, resource).getFirst();
            if (first) {
                sources.addFirst(source);
            } else {
                sources.addLast(source);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot load " + resource, ex);
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

    private static String[] artifactWithIdStart(String start) {
        return concat(
                sinkArtifact(0, "custom_list", true, "id", "value"),
                new String[] {
                        "ioc.sink.artifacts[0].id.strategy=ascending",
                        "ioc.sink.artifacts[0].id.start=" + start
                },
                identity(0, "custom_list", "value"));
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

    private record AmbiguousRoot(String fooBar, Foo foo) {
    }

    private record Foo(String bar) {
    }
}
