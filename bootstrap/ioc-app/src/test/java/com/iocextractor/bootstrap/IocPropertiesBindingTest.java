package com.iocextractor.bootstrap;

import com.iocextractor.application.dataframeimport.contract.DataframeImportCatalog;
import com.iocextractor.application.dataframeimport.model.ImportProcessingMode;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IocPropertiesBindingTest {

    @Test
    void defaultConfigurationBindsThroughBootValidation() {
        contextRunner().run(context -> {
            assertThat(context).hasSingleBean(IocProperties.class);
            assertThat(context).hasBean("configurationPropertiesValidator");
            IocProperties.Lifecycle lifecycle = context.getBean(IocProperties.class).lifecycle();
            assertThat(lifecycle.validity().mode()).isEqualTo(LifecycleValidityMode.DISABLED);
            assertThat(lifecycle.validity().fixedTtl()).isEqualTo(Duration.ofHours(12));
            assertThat(lifecycle.validity().existingRecords()).isEqualTo(ExistingRecordsPolicy.REJECT);
            assertThat(lifecycle.historyRetention()).isEqualTo(Duration.ofDays(30));
            assertThat(lifecycle.historyCleanupInterval()).isEqualTo(Duration.ofHours(1));
            assertThat(lifecycle.receiptRetention()).isEqualTo(Duration.ofDays(30));
            assertThat(lifecycle.reconcile().backstopInterval()).isEqualTo(Duration.ofSeconds(5));
            assertThat(lifecycle.reconcile().batchSize()).isEqualTo(1_000);
            assertThat(lifecycle.clock().maxBackwardSkew()).isEqualTo(Duration.ofSeconds(2));
            assertThat(lifecycle.clock().maxClampDuration()).isEqualTo(Duration.ofSeconds(30));
            assertThat(context.getBean(IocProperties.class).dataframeImport().enabled()).isFalse();
            var importRetention = context.getBean(IocProperties.class)
                    .dataframeImport().runtime().retention();
            assertThat(importRetention.successful().maxAge()).isEqualTo(Duration.ofDays(30));
            assertThat(importRetention.unsuccessful().maxAge()).isEqualTo(Duration.ofDays(90));
            assertThat(importRetention.successful().action()).isEqualTo(RetentionActionType.DELETE);
            assertThat(context).doesNotHaveBean(DataframeImportCatalog.class);
        });
    }

    @Test
    void dataframeImportArchiveRetentionRequiresDestination() {
        contextRunner("ioc.dataframe-import.runtime.retention.successful.action=archive")
                .run(context -> assertThat(fieldErrors(context.getStartupFailure()))
                        .filteredOn(error -> error.getField().contains("retention.successful"))
                        .singleElement()
                        .satisfies(error -> assertThat(error.getDefaultMessage())
                                .contains("archive-dir")));
    }

    @Test
    void bindsAndCompilesAnEnabledDataframeImportCatalog() {
        contextRunner(validDataframeImport())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    IocProperties.DataframeImport properties = context.getBean(IocProperties.class).dataframeImport();
                    assertThat(properties.contracts()).singleElement()
                            .satisfies(contract -> assertThat(contract.mode()).isEqualTo(ImportProcessingMode.AS_IS));
                    IocProperties.DataframeImport.Contract contract = properties.contracts().getFirst();
                    IocProperties.DataframeImport.Artifact artifact = contract.artifacts().getFirst();
                    assertUnmodifiable(properties.sources());
                    assertUnmodifiable(properties.sources().getFirst().contracts());
                    assertUnmodifiable(properties.authorityProfiles());
                    assertUnmodifiable(properties.authorityProfiles().getFirst().artifacts());
                    assertUnmodifiable(properties.contracts());
                    assertUnmodifiable(contract.dialect().nullLiterals());
                    assertUnmodifiable(contract.recognition().requiredColumns());
                    assertUnmodifiable(contract.recognition().optionalColumns());
                    assertUnmodifiable(contract.recognition().ignoredColumns());
                    assertUnmodifiable(contract.recognition().aliases());
                    assertUnmodifiable(contract.artifacts());
                    assertUnmodifiable(artifact.matchKeys());
                    assertUnmodifiable(artifact.columns());
                    assertUnmodifiable(artifact.columns().getFirst().transforms());
                    assertThat(context).hasSingleBean(DataframeImportCatalog.class);
                    assertThat(context.getBean(DataframeImportCatalog.class).fingerprint().value())
                            .matches("[0-9a-f]{64}");
                });
    }

    @Test
    void rejectsEnabledDataframeImportWithoutActiveLifecycleMode() {
        contextRunner(concat(
                validDataframeImport(),
                new String[] { "ioc.lifecycle.validity.mode=disabled" }))
                .run(context -> assertThat(fieldErrors(context.getStartupFailure()))
                        .filteredOn(error -> "dataframeImport.enabled".equals(error.getField()))
                        .singleElement()
                        .satisfies(error -> assertThat(error.getDefaultMessage())
                                .contains("ioc.lifecycle.validity.mode=fixed")));
    }

    @Test
    void reportsAllMissingEnabledDataframeImportSectionsTogether() {
        contextRunner("ioc.dataframe-import.enabled=true")
                .run(context -> assertThat(fieldErrors(context.getStartupFailure()))
                        .filteredOn(error -> "dataframeImport".equals(error.getField()))
                        .extracting(FieldError::getDefaultMessage)
                        .anySatisfy(message -> assertThat(message).contains("sources"))
                        .anySatisfy(message -> assertThat(message).contains("authority-profiles"))
                        .anySatisfy(message -> assertThat(message).contains("contracts")));
    }

    @Test
    void rejectsUnknownDataframeImportContractKey() {
        contextRunner("ioc.dataframe-import.contracts[0].merge-mode=replace")
                .run(context -> assertThat(unboundKeys(context.getStartupFailure()))
                        .containsExactly("ioc.dataframe-import.contracts[0].merge-mode"));
    }

    @Test
    void reportsLifecycleSafetyBoundsThroughSemanticPreflight() {
        contextRunner(
                "ioc.lifecycle.history-retention=0s",
                "ioc.lifecycle.history-cleanup-interval=0s",
                "ioc.lifecycle.receipt-retention=0s",
                "ioc.lifecycle.reconcile.backstop-interval=-1s",
                "ioc.lifecycle.clock.max-backward-skew=0s",
                "ioc.lifecycle.clock.max-clamp-duration=-1s")
                .run(context -> assertThat(fieldErrors(context.getStartupFailure()))
                        .extracting(FieldError::getField)
                        .contains(
                                "lifecycle.historyRetention",
                                "lifecycle.historyCleanupInterval",
                                "lifecycle.receiptRetention",
                                "lifecycle.reconcile.backstopInterval",
                                "lifecycle.clock.maxBackwardSkew",
                                "lifecycle.clock.maxClampDuration"));
    }

    @Test
    void fixed_validity_rejects_a_non_positive_ttl() {
        contextRunner(
                "ioc.lifecycle.validity.mode=fixed",
                "ioc.lifecycle.validity.fixed-ttl=0s")
                .run(context -> assertThat(fieldErrors(context.getStartupFailure()))
                        .extracting(FieldError::getField)
                        .contains("lifecycle.validity.fixedTtl"));
    }

    @Test
    void processing_fingerprint_excludes_lifecycle_timing_but_tracks_row_policy() {
        String twelveHours = processingFingerprint("ioc.lifecycle.validity.fixed-ttl=12h");
        String twentyFourHours = processingFingerprint("ioc.lifecycle.validity.fixed-ttl=24h");
        String changedPipeline = processingFingerprint("ioc.pipeline.deduplicate=false");

        assertThat(twentyFourHours).isEqualTo(twelveHours);
        assertThat(changedPipeline).isNotEqualTo(twelveHours);
    }

    @Test
    void boundConfigurationCollectionsAreImmutableSnapshots() {
        contextRunner().run(context -> {
            IocProperties properties = context.getBean(IocProperties.class);
            IocProperties.Sink.Artifact masks = properties.sink().artifacts().stream()
                    .filter(artifact -> "masks".equals(artifact.name()))
                    .findFirst()
                    .orElseThrow();
            IocProperties.Sink.Artifact ipList = properties.sink().artifacts().stream()
                    .filter(artifact -> "ip_list".equals(artifact.name()))
                    .findFirst()
                    .orElseThrow();
            IocProperties.Sink.Artifact.Column transformed = masks.columns().stream()
                    .filter(column -> column.transform() != null && !column.transform().isEmpty())
                    .findFirst()
                    .orElseThrow();

            assertUnmodifiable(properties.patterns());
            assertUnmodifiable(properties.artifactIdentity().artifacts());
            assertUnmodifiable(properties.artifactIdentity().artifacts().getFirst().keyColumns());
            assertUnmodifiable(properties.classify().rules());
            assertUnmodifiable(properties.classify().rules().getFirst().when());
            assertUnmodifiable(properties.export().profiles());
            assertUnmodifiable(properties.export().profiles().getFirst().artifacts());
            assertUnmodifiable(properties.ingestion().patterns().include());
            assertUnmodifiable(properties.ingestion().patterns().exclude());
            assertUnmodifiable(properties.maintenance().retention().targets());
            assertUnmodifiable(properties.refang().rules());
            assertUnmodifiable(properties.sink().artifacts());
            assertUnmodifiable(masks.accepts());
            assertUnmodifiable(ipList.include());
            assertUnmodifiable(masks.exclude());
            assertUnmodifiable(masks.columns());
            assertUnmodifiable(transformed.transform());
            assertUnmodifiable(properties.source().sectionMarkers());
        });
    }

    @Test
    void rootMapSnapshotPreservesNullValueAndCallerIsolation() {
        contextRunner().run(context -> {
            IocProperties source = context.getBean(IocProperties.class);
            Map<IndicatorType, String> patterns = new LinkedHashMap<>(source.patterns());
            patterns.put(IndicatorType.MD5, null);
            IocProperties snapshot = new IocProperties(
                    source.engine(), source.runtime(), source.storage(), source.source(), source.refang(), patterns,
                    source.classify(), source.sink(), source.pipeline(), source.ingestion(),
                    source.artifactIdentity(), source.dataframeImport(), source.export(), source.sync(), source.maintenance(),
                    source.lifecycle(), source.observability());

            patterns.clear();

            assertThat(snapshot.patterns()).containsEntry(IndicatorType.MD5, null).isNotEmpty();
            assertUnmodifiable(snapshot.patterns());
        });
    }

    @Test
    void listSnapshotsPreserveNullForCollectAllValidation() {
        var sectionMarkers = new ArrayList<>(Arrays.asList("header", null));
        var source = new IocProperties.Source("auto", "auto", sectionMarkers);
        sectionMarkers.clear();

        assertThat(source.sectionMarkers()).containsExactly("header", null);
        assertUnmodifiable(source.sectionMarkers());

        var invalid = new IocProperties.Sink.Artifact(
                "artifact", true, "artifact.csv", null, null, null, null, null);
        try (var validatorFactory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            assertThat(validatorFactory.getValidator().validate(invalid))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .containsExactlyInAnyOrder("accepts", "columns");
        }
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
    void rejectsProjectionRootAndReportsOtherSemanticErrorsTogether() {
        contextRunner(
                "ioc.sink.artifacts[0].path=/",
                "ioc.sync.retry.max-attempts=0")
                .run(context -> assertThat(fieldErrors(context.getStartupFailure()))
                        .extracting(FieldError::getField)
                        .contains(
                                "sink.artifacts[0].path",
                                "sync.retry.maxAttempts"));
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
                "ioc.ingestion.ledger.type=FILE",
                "ioc.lifecycle.validity.mode=FIXED",
                "ioc.lifecycle.validity.existing-records=EXPIRE")
                .run(context -> {
                    IocProperties props = context.getBean(IocProperties.class);
                    assertThat(props.engine()).isEqualTo(EngineType.RE2J);
                    assertThat(props.runtime().mode()).isEqualTo(RuntimeMode.DAEMON);
                    assertThat(props.observability().mode()).isEqualTo(ObservabilityMode.DAEMON);
                    assertThat(props.storage().service().type()).isEqualTo(StorageType.JDBC);
                    assertThat(ArtifactIdStrategy.parse("DESCENDING")).isEqualTo(ArtifactIdStrategy.DESCENDING);
                    assertThat(props.artifactIdentity().artifacts().get(2).keyMode())
                            .isEqualTo(ArtifactKeyMode.COMPOSITE);
                    assertThat(ArtifactKeyMode.parse("first_non_empty")).isEqualTo(ArtifactKeyMode.FIRST_NON_EMPTY);
                    assertThat(props.export().trigger().type()).isEqualTo(ExportTriggerType.QUIET_PERIOD);
                    assertThat(ExportOutputMode.parse("Complete")).isEqualTo(ExportOutputMode.COMPLETE);
                    assertThat(props.ingestion().ledger().type()).isEqualTo(IngestionLedgerType.FILE);
                    assertThat(props.lifecycle().validity().mode()).isEqualTo(LifecycleValidityMode.FIXED);
                    assertThat(props.lifecycle().validity().existingRecords()).isEqualTo(ExistingRecordsPolicy.EXPIRE);
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
    void rejectsInvalidLifecycleSelectorsWithSupportedValues() {
        contextRunner("ioc.lifecycle.validity.mode=forever")
                .run(context -> assertThat(causeMessages(context.getStartupFailure()))
                        .contains("ioc.lifecycle.validity.mode")
                        .contains("disabled, fixed"));

        contextRunner("ioc.lifecycle.validity.existing-records=keep")
                .run(context -> assertThat(causeMessages(context.getStartupFailure()))
                        .contains("ioc.lifecycle.validity.existing-records")
                        .contains("reject, expire"));
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
    void rejectsSmbEncryptionTypoDuringBinding() {
        contextRunner(
                "ioc.sync.endpoints[0].name=share",
                "ioc.sync.endpoints[0].transport=smb",
                "ioc.sync.endpoints[0].smb.encryption=mandatory")
                .run(context -> assertThat(causeMessages(context.getStartupFailure()))
                        .contains("ioc.sync.endpoints[].smb.encryption")
                        .contains("disabled, preferred, required"));
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
    void rejectsUnsupportedIngestionConcurrency() {
        contextRunner("ioc.ingestion.concurrency=2")
                .run(context -> assertThat(fieldErrors(context.getStartupFailure()))
                        .filteredOn(error -> "ingestion.concurrency".equals(error.getField()))
                        .singleElement()
                        .satisfies(error -> assertThat(error.getDefaultMessage())
                                .contains("keep it at 1")
                                .contains("parallel ingestion is not supported")));
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
    void mapsLegacySmbEncryptTrueToRequired() {
        contextRunner(legacySmbEndpoint(true))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    IocProperties.Sync.Endpoint.Smb smb = context.getBean(IocProperties.class)
                            .sync().endpoints().getFirst().smb();
                    assertThat(smb.encryption()).isNull();
                    assertThat(smb.encrypt()).isTrue();
                    assertThat(smb.resolvedEncryption()).isEqualTo(SmbEncryptionMode.REQUIRED);
                });
    }

    @Test
    void mapsLegacySmbEncryptFalseToDisabled() {
        contextRunner(legacySmbEndpoint(false))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    IocProperties.Sync.Endpoint.Smb smb = context.getBean(IocProperties.class)
                            .sync().endpoints().getFirst().smb();
                    assertThat(smb.resolvedEncryption()).isEqualTo(SmbEncryptionMode.DISABLED);
                });
    }

    @Test
    void bindsCustomSmbPort() {
        contextRunner(concat(
                legacySmbEndpoint(true),
                new String[] { "ioc.sync.endpoints[0].smb.port=1445" }))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    IocProperties.Sync.Endpoint.Smb smb = context.getBean(IocProperties.class)
                            .sync().endpoints().getFirst().smb();
                    assertThat(smb.port()).isEqualTo(1_445);
                    assertThat(smb.resolvedPort()).isEqualTo(1_445);
                });
    }

    @Test
    void rejectsSmbPortBelowTcpRange() {
        contextRunner(concat(
                legacySmbEndpoint(true),
                new String[] { "ioc.sync.endpoints[0].smb.port=0" }))
                .run(context -> assertThat(fieldErrors(context.getStartupFailure()))
                        .extracting(FieldError::getField)
                        .contains("sync.endpoints[0].smb.port"));
    }

    @Test
    void rejectsSmbPortAboveTcpRange() {
        contextRunner(concat(
                legacySmbEndpoint(true),
                new String[] { "ioc.sync.endpoints[0].smb.port=65536" }))
                .run(context -> assertThat(fieldErrors(context.getStartupFailure()))
                        .extracting(FieldError::getField)
                        .contains("sync.endpoints[0].smb.port"));
    }

    @Test
    void rejectsCurrentAndLegacySmbEncryptionKeysTogether() {
        contextRunner(concat(
                legacySmbEndpoint(true),
                new String[] { "ioc.sync.endpoints[0].smb.encryption=required" }))
                .run(context -> assertThat(fieldErrors(context.getStartupFailure()))
                        .filteredOn(error -> "sync.endpoints[0].smb.encryption".equals(error.getField()))
                        .singleElement()
                        .satisfies(error -> assertThat(error.getDefaultMessage())
                                .contains("cannot be configured together")
                                .contains("keep only encryption")));
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
    void recognizesLegacySmbEncryptEnvironmentKey() {
        IocEnvironmentPropertyMatcher.MatchResult result = new IocEnvironmentPropertyMatcher()
                .match("IOC_SYNC_ENDPOINTS_0_SMB_ENCRYPT");

        assertThat(result.canonicalNames()).containsExactly("ioc.sync.endpoints[0].smb.encrypt");
        assertThat(result.isKnown()).isTrue();
        assertThat(result.isAmbiguous()).isFalse();
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
    void acceptsOnlyTheExactV020IdentityShapesWithoutVersionedDefinitions() {
        contextRunner(v020ArtifactIdentities())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(IocProperties.class).artifactIdentity().artifacts())
                            .allMatch(V020ArtifactIdentityCompatibility::appliesTo);
                });
    }

    @Test
    void rejectsIncompleteIdentityThatOnlyResemblesTheV020Shape() {
        contextRunner(concat(
                sinkArtifact(0, "masks", true, "id", "mask", "source"),
                new String[] {
                        "ioc.artifact-identity.artifacts[0].name=masks",
                        "ioc.artifact-identity.artifacts[0].key-columns[0]=mask",
                        "ioc.artifact-identity.artifacts[0].key-columns[1]=source"
                }))
                .run(context -> assertThat(fieldErrors(context.getStartupFailure()))
                        .extracting(FieldError::getField)
                        .contains("artifactIdentity.artifacts[0].recordKey"));
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

    private static void assertUnmodifiable(Collection<?> values) {
        assertThat(values).isNotNull();
        assertThatThrownBy(values::clear).isInstanceOf(UnsupportedOperationException.class);
    }

    private static String[] legacySmbEndpoint(boolean encrypt) {
        return new String[] {
                "ioc.sync.endpoints[0].name=share",
                "ioc.sync.endpoints[0].transport=smb",
                "ioc.sync.endpoints[0].smb.host=server",
                "ioc.sync.endpoints[0].smb.share=share",
                "ioc.sync.endpoints[0].smb.username=user",
                "ioc.sync.endpoints[0].smb.password=secret",
                "ioc.sync.endpoints[0].smb.encrypt=" + encrypt
        };
    }

    private static void assertUnmodifiable(Map<?, ?> values) {
        assertThat(values).isNotNull();
        assertThatThrownBy(values::clear).isInstanceOf(UnsupportedOperationException.class);
    }

    private ApplicationContextRunner contextRunner(String... overrides) {
        // TestPropertyValues merges indexed lists over application.yml; it cannot shorten default YAML lists.
        return new ApplicationContextRunner()
                .withInitializer(IocPropertiesBindingTest::addDefaultApplicationYaml)
                .withUserConfiguration(TestConfig.class)
                .withPropertyValues(overrides);
    }

    private String processingFingerprint(String... overrides) {
        var fingerprint = new AtomicReference<String>();
        contextRunner(overrides).run(context -> {
            assertThat(context).hasNotFailed();
            fingerprint.set(ProcessingPolicyFingerprint.from(context.getBean(IocProperties.class)));
        });
        return Objects.requireNonNull(fingerprint.get(), "processing fingerprint");
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
        assertThat(validation).as(causeMessages(failure)).isNotNull();
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
        values.add("%s.record-key=%s-row-v1".formatted(prefix, name));
        for (int i = 0; i < keyColumns.length; i++) {
            values.add("%s.key-columns[%d]=%s".formatted(prefix, i, keyColumns[i]));
        }
        return values.toArray(String[]::new);
    }

    private static String[] v020ArtifactIdentities() {
        return new String[] {
                "ioc.artifact-identity.artifacts[0].name=masks",
                "ioc.artifact-identity.artifacts[0].key-columns[0]=mask",
                "ioc.artifact-identity.artifacts[1].name=ip_list",
                "ioc.artifact-identity.artifacts[1].key-columns[0]=ip",
                "ioc.artifact-identity.artifacts[2].name=address_blacklist",
                "ioc.artifact-identity.artifacts[2].key-columns[0]=forbidden_url",
                "ioc.artifact-identity.artifacts[2].key-columns[1]=forbidden_ip",
                "ioc.artifact-identity.artifacts[2].key-mode=first-non-empty",
                "ioc.artifact-identity.artifacts[3].name=hashes",
                "ioc.artifact-identity.artifacts[3].key-columns[0]=hash_md5",
                "ioc.artifact-identity.artifacts[3].key-columns[1]=hash_sha1",
                "ioc.artifact-identity.artifacts[3].key-columns[2]=hash_sha256",
                "ioc.artifact-identity.artifacts[3].key-mode=first-non-empty"
        };
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

    private static String[] validDataframeImport() {
        return new String[] {
                "ioc.lifecycle.validity.mode=fixed",
                "ioc.dataframe-import.enabled=true",
                "ioc.dataframe-import.sources[0].id=local",
                "ioc.dataframe-import.sources[0].transport=local",
                "ioc.dataframe-import.sources[0].location=./var/import",
                "ioc.dataframe-import.sources[0].contracts[0]=ip-list-v1",
                "ioc.dataframe-import.sources[0].authority=standard",
                "ioc.dataframe-import.authority-profiles[0].id=standard",
                "ioc.dataframe-import.authority-profiles[0].artifacts[0]=ip_list",
                "ioc.dataframe-import.authority-profiles[0].maximum-merge-policy=fill-missing",
                "ioc.dataframe-import.contracts[0].id=ip-list-v1",
                "ioc.dataframe-import.contracts[0].version=1",
                "ioc.dataframe-import.contracts[0].charset=UTF-8",
                "ioc.dataframe-import.contracts[0].dialect.delimiter=;",
                "ioc.dataframe-import.contracts[0].dialect.quote=\"",
                "ioc.dataframe-import.contracts[0].dialect.record-separator=crlf-or-lf",
                "ioc.dataframe-import.contracts[0].dialect.header-required=true",
                "ioc.dataframe-import.contracts[0].mode=as-is",
                "ioc.dataframe-import.contracts[0].routing=target-only",
                "ioc.dataframe-import.contracts[0].row-failure-policy=accept-valid",
                "ioc.dataframe-import.contracts[0].duplicate-policy=coalesce",
                "ioc.dataframe-import.contracts[0].renew-unchanged=true",
                "ioc.dataframe-import.contracts[0].formula-policy=reject",
                "ioc.dataframe-import.contracts[0].merge-default=fill-missing",
                "ioc.dataframe-import.contracts[0].recognition.required-columns[0]=ip",
                "ioc.dataframe-import.contracts[0].artifacts[0].name=ip_list",
                "ioc.dataframe-import.contracts[0].artifacts[0].role=primary",
                "ioc.dataframe-import.contracts[0].artifacts[0].record-key=ip-row-v1",
                "ioc.dataframe-import.contracts[0].artifacts[0].match-keys[0]=ip-v1",
                "ioc.dataframe-import.contracts[0].artifacts[0].columns[0].target=ip",
                "ioc.dataframe-import.contracts[0].artifacts[0].columns[0].source=ip"
        };
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
    @Import({ ConfigPreflightConfiguration.class, DataframeImportConfiguration.class })
    static class TestConfig {
    }

    private record AmbiguousRoot(String fooBar, Foo foo) {
    }

    private record Foo(String bar) {
    }
}
