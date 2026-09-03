package com.iocextractor.bootstrap;

import com.iocextractor.domain.model.IndicatorType;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.validation.MapBindingResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IocConfigPreflightTest {

    @Test
    void supportsOnlyTheTypedRootAndIgnoresForeignTargets() {
        IocConfigPreflight preflight = new IocConfigPreflight();
        var errors = errors();

        preflight.validate("not ioc properties", errors);

        assertThat(preflight.supports(IocProperties.class)).isTrue();
        assertThat(preflight.supports(Object.class)).isFalse();
        assertThat(errors.hasErrors()).isFalse();
    }

    @Test
    void rejectsNonPositiveLifecycleDurationsAndParallelIngestion() {
        IocProperties source = defaults();
        IocProperties.Lifecycle invalidLifecycle = new IocProperties.Lifecycle(
                new IocProperties.Lifecycle.Validity(
                        LifecycleValidityMode.FIXED, Duration.ZERO, ExistingRecordsPolicy.REJECT),
                Duration.ZERO,
                Duration.ofSeconds(-1),
                Duration.ZERO,
                new IocProperties.Lifecycle.Reconcile(Duration.ZERO, 1),
                new IocProperties.Lifecycle.ClockSafety(Duration.ZERO, Duration.ofSeconds(-1)));
        IocProperties.Ingestion ingestion = new IocProperties.Ingestion(
                source.ingestion().dirs(), source.ingestion().patterns(), source.ingestion().detect(),
                source.ingestion().stability(), source.ingestion().retry(), source.ingestion().ledger(), 2);

        var errors = validate(withLifecycleAndIngestion(source, invalidLifecycle, ingestion));

        assertThat(fields(errors)).contains(
                "lifecycle.validity.fixedTtl",
                "lifecycle.historyRetention",
                "lifecycle.historyCleanupInterval",
                "lifecycle.receiptRetention",
                "lifecycle.reconcile.backstopInterval",
                "lifecycle.clock.maxBackwardSkew",
                "lifecycle.clock.maxClampDuration",
                "ingestion.concurrency");
    }

    @Test
    void requiresFixedLifecycleWhenManagedImportIsEnabled() {
        IocProperties source = defaults();
        IocProperties.DataframeImport enabled = new IocProperties.DataframeImport(
                true,
                source.dataframeImport().sources(),
                source.dataframeImport().authorityProfiles(),
                source.dataframeImport().contracts(),
                source.dataframeImport().runtime());

        var errors = validate(withDataframeImport(source, enabled));

        assertThat(fields(errors)).contains("dataframeImport.enabled");
    }

    @Test
    void rejectsInvalidRetryEndpointAndSmbContracts() {
        IocProperties source = defaults();
        IocProperties.Sync.Endpoint missingSmb = new IocProperties.Sync.Endpoint(
                "shared", SyncTransport.SMB, null);
        IocProperties.Sync.Endpoint conflictingSmb = new IocProperties.Sync.Endpoint(
                "shared", SyncTransport.SMB,
                new IocProperties.Sync.Endpoint.Smb(
                        "host", null, "share", null, "user", "secret",
                        SmbEncryptionMode.REQUIRED, true,
                        Duration.ZERO, Duration.ofSeconds(-1), Duration.ZERO));
        IocProperties.Sync sync = new IocProperties.Sync(
                true,
                new IocProperties.Sync.Retry(0, Duration.ofSeconds(2), 0.5d, Duration.ofSeconds(1), false),
                List.of(missingSmb, conflictingSmb),
                source.sync().fetch(),
                source.sync().publish());

        var errors = validate(withSync(source, sync));

        assertThat(fields(errors)).contains(
                "sync.retry.maxAttempts",
                "sync.retry.multiplier",
                "sync.retry.maxBackoff",
                "sync.endpoints[0].smb",
                "sync.endpoints[1].name",
                "sync.endpoints[1].smb.encryption",
                "sync.endpoints[1].smb.connectTimeout",
                "sync.endpoints[1].smb.requestTimeout",
                "sync.endpoints[1].smb.idleTimeout");
    }

    @Test
    void rejectsNonPositiveRetryBoundsIndependently() {
        IocProperties source = defaults();
        IocProperties.Sync sync = new IocProperties.Sync(
                true,
                new IocProperties.Sync.Retry(1, Duration.ZERO, 1.0d, Duration.ofSeconds(-1), false),
                source.sync().endpoints(), source.sync().fetch(), source.sync().publish());

        var errors = validate(withSync(source, sync));

        assertThat(fields(errors)).contains("sync.retry.backoff", "sync.retry.maxBackoff");
    }

    @Test
    void leavesStructuralNullCollectionsToBeanValidationWithoutCrashing() {
        IocProperties source = defaults();
        IocProperties properties = new IocProperties(
                source.engine(), source.runtime(), source.storage(), source.source(), source.refang(),
                source.patterns(), source.classify(), new IocProperties.Sink(source.sink().csv(), null),
                source.pipeline(), source.ingestion(), new IocProperties.ArtifactIdentity(null),
                source.dataframeImport(),
                new IocProperties.Export(
                        source.export().enabled(), source.export().root(), source.export().trigger(), null,
                        source.export().retention()),
                new IocProperties.Sync(
                        source.sync().enabled(), source.sync().retry(), null,
                        new IocProperties.Sync.Fetch(false, Duration.ofMinutes(1), null),
                        new IocProperties.Sync.Publish(false, Duration.ofMinutes(1), null)),
                source.maintenance(), source.lifecycle(), source.observability());

        var errors = validate(properties);

        assertThat(errors.hasErrors()).isFalse();
    }

    @Test
    void rejectsInvalidFetchAndPublishReferencesAndCadence() {
        IocProperties source = defaults();
        IocProperties.Sync.Fetch.Source firstSource = new IocProperties.Sync.Fetch.Source(
                "duplicate", "missing", "/incoming", List.of("*"), List.of(),
                new IocProperties.Sync.Fetch.Source.ChangeNotify(true, Duration.ZERO));
        IocProperties.Sync.Fetch.Source secondSource = new IocProperties.Sync.Fetch.Source(
                "duplicate", "missing", "/other", List.of("*"), List.of(), null);
        IocProperties.Sync.Publish.Target firstTarget = new IocProperties.Sync.Publish.Target(
                "duplicate", "missing", "/out", "missing-profile");
        IocProperties.Sync.Publish.Target secondTarget = new IocProperties.Sync.Publish.Target(
                "duplicate", "missing", "/other", "missing-profile");
        IocProperties.Sync sync = new IocProperties.Sync(
                true,
                source.sync().retry(),
                List.of(),
                new IocProperties.Sync.Fetch(true, Duration.ZERO, List.of(firstSource, secondSource)),
                new IocProperties.Sync.Publish(true, Duration.ZERO, List.of(firstTarget, secondTarget)));

        var errors = validate(withSync(source, sync));

        assertThat(fields(errors)).contains(
                "sync.fetch.interval",
                "sync.fetch.sources[0].endpoint",
                "sync.fetch.sources[0].changeNotify.debounce",
                "sync.fetch.sources[1].name",
                "sync.publish.interval",
                "sync.publish.targets[0].endpoint",
                "sync.publish.targets[0].exportProfile",
                "sync.publish.targets[1].name");
    }

    @Test
    void rejectsMalformedSinkArtifactsAndColumns() {
        IocProperties source = defaults();
        IocProperties.Sink.Artifact.Column value = new IocProperties.Sink.Artifact.Column(
                "value", "value", null, null, null, List.of());
        IocProperties.Sink.Artifact.Column duplicate = new IocProperties.Sink.Artifact.Column(
                "value", "value", null, null, null, List.of());
        IocProperties.Sink.Artifact.Column blank = new IocProperties.Sink.Artifact.Column(
                " ", "value", null, null, null, List.of());
        List<IocProperties.Sink.Artifact.Column> malformedColumns =
                new ArrayList<>(Arrays.asList(null, value, duplicate, blank));
        IocProperties.Sink.Artifact rootPath = new IocProperties.Sink.Artifact(
                "orphan", true, "/", List.of(IndicatorType.DOMAIN), List.of(), List.of(),
                new IocProperties.Sink.Artifact.Id(ArtifactIdStrategy.ASCENDING, IdStart.explicit(7)),
                malformedColumns);
        IocProperties.Sink.Artifact invalidPath = new IocProperties.Sink.Artifact(
                "orphan", true, "invalid\0path", List.of(IndicatorType.DOMAIN), List.of(), List.of(),
                null, List.of(value));
        IocProperties.Sink.Artifact noColumns = new IocProperties.Sink.Artifact(
                "no-columns", false, "output.csv", List.of(IndicatorType.DOMAIN), List.of(), List.of(),
                null, null);
        List<IocProperties.Sink.Artifact> artifacts = new ArrayList<>(Arrays.asList(
                null, rootPath, invalidPath, noColumns));
        IocProperties.Sink sink = new IocProperties.Sink(source.sink().csv(), artifacts);

        var errors = validate(withSink(source, sink));

        assertThat(fields(errors)).contains(
                "sink.artifacts[0]",
                "sink.artifacts[1].columns[0]",
                "sink.artifacts[1].columns[2].name",
                "sink.artifacts[1].path",
                "sink.artifacts[1].id.start",
                "sink.artifacts[2].name",
                "sink.artifacts[2].path",
                "sink.artifacts[1].name");
        assertThat(noColumns.hasPublicIdColumn()).isFalse();
    }

    @Test
    void rejectsMalformedIdentityDefinitionsAndMatchKeys() {
        IocProperties source = defaults();
        IocProperties.ArtifactIdentity.Artifact.MatchKey empty =
                new IocProperties.ArtifactIdentity.Artifact.MatchKey("duplicate", List.of());
        IocProperties.ArtifactIdentity.Artifact.MatchKey malformed =
                new IocProperties.ArtifactIdentity.Artifact.MatchKey(
                        "duplicate", new ArrayList<>(Arrays.asList("", "mask", "mask", "missing")));
        IocProperties.ArtifactIdentity.Artifact unknown =
                new IocProperties.ArtifactIdentity.Artifact(
                        "unknown", List.of("value"), ArtifactKeyMode.COMPOSITE, 1, null, null);
        IocProperties.ArtifactIdentity.Artifact masks =
                new IocProperties.ArtifactIdentity.Artifact(
                        "masks", new ArrayList<>(Arrays.asList("", "missing")),
                        ArtifactKeyMode.COMPOSITE, 1, "mask-row-v1",
                        new ArrayList<>(Arrays.asList(null, empty, malformed)));
        IocProperties.ArtifactIdentity.Artifact duplicateMasks =
                new IocProperties.ArtifactIdentity.Artifact(
                        "masks", List.of("mask"), ArtifactKeyMode.COMPOSITE, 1, "mask-row-v1", List.of());
        IocProperties.ArtifactIdentity identity = new IocProperties.ArtifactIdentity(
                new ArrayList<>(Arrays.asList(null, unknown, masks, duplicateMasks)));

        var errors = validate(withArtifactIdentity(source, identity));

        assertThat(fields(errors)).contains(
                "artifactIdentity.artifacts[0]",
                "artifactIdentity.artifacts[1].name",
                "artifactIdentity.artifacts[1].recordKey",
                "artifactIdentity.artifacts[2].keyColumns[0]",
                "artifactIdentity.artifacts[2].keyColumns[1]",
                "artifactIdentity.artifacts[2].matchKeys[0]",
                "artifactIdentity.artifacts[2].matchKeys[1].keyColumns",
                "artifactIdentity.artifacts[2].matchKeys[2].name",
                "artifactIdentity.artifacts[2].matchKeys[2].keyColumns[0]",
                "artifactIdentity.artifacts[2].matchKeys[2].keyColumns[2]",
                "artifactIdentity.artifacts[2].matchKeys[2].keyColumns[3]",
                "artifactIdentity.artifacts[3].name");
    }

    private MapBindingResult validate(IocProperties properties) {
        var errors = errors();
        new IocConfigPreflight().validate(properties, errors);
        return errors;
    }

    private MapBindingResult errors() {
        return new MapBindingResult(new LinkedHashMap<>(), "ioc");
    }

    private List<String> fields(MapBindingResult errors) {
        return errors.getFieldErrors().stream().map(error -> error.getField()).toList();
    }

    private IocProperties defaults() {
        try {
            var source = new YamlPropertySourceLoader()
                    .load("defaults", new ClassPathResource("application.yml")).getFirst();
            ApplicationConversionService conversionService = new ApplicationConversionService();
            conversionService.addConverter(String.class, IdStart.class, IdStart::parse);
            conversionService.addConverter(Number.class, IdStart.class, IdStart::from);
            return new Binder(ConfigurationPropertySources.from(source), null, conversionService)
                    .bind("ioc", Bindable.of(IocProperties.class))
                    .orElseThrow(() -> new IllegalStateException("default ioc properties did not bind"));
        } catch (Exception exception) {
            throw new IllegalStateException("failed to load default ioc properties", exception);
        }
    }

    private IocProperties withLifecycleAndIngestion(
            IocProperties source,
            IocProperties.Lifecycle lifecycle,
            IocProperties.Ingestion ingestion) {
        return copy(source, source.sink(), source.artifactIdentity(), source.dataframeImport(),
                source.sync(), lifecycle, ingestion);
    }

    private IocProperties withDataframeImport(
            IocProperties source,
            IocProperties.DataframeImport dataframeImport) {
        return copy(source, source.sink(), source.artifactIdentity(), dataframeImport,
                source.sync(), source.lifecycle(), source.ingestion());
    }

    private IocProperties withSync(IocProperties source, IocProperties.Sync sync) {
        return copy(source, source.sink(), source.artifactIdentity(), source.dataframeImport(),
                sync, source.lifecycle(), source.ingestion());
    }

    private IocProperties withSink(IocProperties source, IocProperties.Sink sink) {
        return copy(source, sink, source.artifactIdentity(), source.dataframeImport(),
                source.sync(), source.lifecycle(), source.ingestion());
    }

    private IocProperties withArtifactIdentity(
            IocProperties source,
            IocProperties.ArtifactIdentity artifactIdentity) {
        return copy(source, source.sink(), artifactIdentity, source.dataframeImport(),
                source.sync(), source.lifecycle(), source.ingestion());
    }

    private IocProperties copy(
            IocProperties source,
            IocProperties.Sink sink,
            IocProperties.ArtifactIdentity artifactIdentity,
            IocProperties.DataframeImport dataframeImport,
            IocProperties.Sync sync,
            IocProperties.Lifecycle lifecycle,
            IocProperties.Ingestion ingestion) {
        return new IocProperties(
                source.engine(), source.runtime(), source.storage(), source.source(), source.refang(),
                source.patterns(), source.classify(), sink, source.pipeline(), ingestion,
                artifactIdentity, dataframeImport, source.export(), sync, source.maintenance(),
                lifecycle, source.observability());
    }
}
