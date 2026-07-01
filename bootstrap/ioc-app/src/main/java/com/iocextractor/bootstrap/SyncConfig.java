package com.iocextractor.bootstrap;

import com.iocextractor.adapter.out.sink.csv.FileSystemCompletedSliceCatalog;
import com.iocextractor.adapter.out.store.jdbc.JdbcPublishLedger;
import com.iocextractor.adapter.out.store.jdbc.JdbcRemoteFetchLedger;
import com.iocextractor.adapter.out.transport.smb.SmbEndpointSettings;
import com.iocextractor.adapter.out.transport.smb.SmbFileTransport;
import com.iocextractor.application.port.in.sync.ArtifactPublishUseCase;
import com.iocextractor.application.port.in.sync.RemoteFetchUseCase;
import com.iocextractor.application.port.in.sync.ValidateSyncSelectionUseCase;
import com.iocextractor.application.port.out.export.SliceManifestCodec;
import com.iocextractor.application.port.out.export.SliceRetentionGuard;
import com.iocextractor.application.port.out.sync.CompletedSliceCatalog;
import com.iocextractor.application.port.out.sync.PublishLedger;
import com.iocextractor.application.port.out.sync.RemoteFetchLedger;
import com.iocextractor.application.sync.ArtifactPublishService;
import com.iocextractor.application.sync.PublishLedgerSliceRetentionGuard;
import com.iocextractor.application.sync.PublishTarget;
import com.iocextractor.application.sync.RemoteFetchService;
import com.iocextractor.application.sync.RemoteFetchSource;
import com.iocextractor.application.sync.RemoteSourceMonitor;
import com.iocextractor.application.sync.Retrier;
import com.iocextractor.application.sync.RetryPolicy;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.sink.DiagnosticSink;
import com.iocextractor.platform.concurrent.BoundedKeyedSerialExecutor;
import com.iocextractor.platform.concurrent.KeyedSerialExecutor;
import com.iocextractor.platform.events.ControlEventObserver;
import com.iocextractor.platform.events.ControlEventPublisher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;

/** Composition root segment for remote sync transports, use cases and daemon lifecycle. */
@Configuration
public class SyncConfig {

    private static final int DEFAULT_SYNC_QUEUE_PER_ENDPOINT = 64;
    private static final int DEFAULT_FETCH_BATCH_SIZE = 128;

    @Bean
    public ValidateSyncSelectionUseCase validateSyncSelectionUseCase(IocProperties props) {
        return new SyncSelectionCatalog(props);
    }

    @Bean
    public SyncHealthState syncHealthState(Clock clock) {
        return new SyncHealthState(clock);
    }

    @Bean
    @Lazy
    @ConditionalOnProperty(prefix = "ioc.sync", name = "enabled", havingValue = "true")
    public TransportRegistry transportRegistry(IocProperties props) {
        List<IocProperties.Sync.Endpoint> smbEndpoints = props.sync().endpoints().stream()
                .filter(endpoint -> "smb".equalsIgnoreCase(endpoint.transport()))
                .toList();
        rejectUnsupportedTransports(props, smbEndpoints.size());

        SmbFileTransport smb = new SmbFileTransport(smbEndpoints.stream()
                .map(this::smbSettings)
                .toList());
        List<TransportRegistry.Binding> bindings = smbEndpoints.stream()
                .map(endpoint -> new TransportRegistry.Binding(
                        endpoint.name(), smb, smb::closeIdle, smb))
                .toList();
        return new TransportRegistry(bindings);
    }

    @Bean
    @Lazy
    @ConditionalOnExpression("'${ioc.sync.enabled:false}' == 'true' && "
            + "'${ioc.storage.service.type:disabled}' == 'jdbc'")
    public RemoteFetchLedger remoteFetchLedger(LazyServiceStorage storage) {
        return new JdbcRemoteFetchLedger(storage.dataSource());
    }

    @Bean
    @Lazy
    @ConditionalOnExpression("'${ioc.sync.enabled:false}' == 'true' && "
            + "'${ioc.storage.service.type:disabled}' == 'jdbc'")
    public PublishLedger publishLedger(LazyServiceStorage storage, Clock clock) {
        return new JdbcPublishLedger(storage.dataSource(), clock);
    }

    @Bean
    @Lazy
    @ConditionalOnProperty(prefix = "ioc.sync", name = "enabled", havingValue = "true")
    public CompletedSliceCatalog completedSliceCatalog(IocProperties props,
                                                       SliceManifestCodec codec,
                                                       DiagnosticSink diagnostics,
                                                       Clock clock) {
        return new FileSystemCompletedSliceCatalog(
                Path.of(props.export().root()), codec, diagnostics, new DiagnosticFactory(clock));
    }

    @Bean
    @Lazy
    @ConditionalOnProperty(prefix = "ioc.sync", name = "enabled", havingValue = "true")
    public Retrier syncRetrier(IocProperties props) {
        IocProperties.Sync.Retry retry = props.sync().retry();
        return new Retrier(new RetryPolicy(
                retry.maxAttempts(), retry.backoff(), retry.multiplier(),
                retry.maxBackoff(), retry.jitter()));
    }

    @Bean
    @Lazy
    @ConditionalOnExpression("'${ioc.sync.enabled:false}' == 'true' && "
            + "'${ioc.sync.fetch.enabled:false}' == 'true' && "
            + "'${ioc.storage.service.type:disabled}' == 'jdbc'")
    public RemoteFetchUseCase remoteFetchUseCase(
            TransportRegistry transports,
            RemoteFetchLedger ledger,
            Retrier syncRetrier,
            IocProperties props,
            Clock clock) {
        return new RemoteFetchService(
                transports, ledger, fetchSources(props), Path.of(props.ingestion().dirs().inbox()),
                syncRetrier, clock);
    }

    @Bean
    @Lazy
    @ConditionalOnExpression("'${ioc.sync.enabled:false}' == 'true' && "
            + "'${ioc.sync.fetch.enabled:false}' == 'true' && "
            + "'${ioc.storage.service.type:disabled}' == 'jdbc'")
    public RemoteSourceMonitor remoteSourceMonitor(
            TransportRegistry transports,
            RemoteFetchLedger ledger,
            IocProperties props,
            Clock clock) {
        return new RemoteSourceMonitor(
                transports, ledger, fetchSources(props), DEFAULT_FETCH_BATCH_SIZE, clock);
    }

    @Bean
    @Lazy
    @ConditionalOnExpression("'${ioc.sync.enabled:false}' == 'true' && "
            + "'${ioc.sync.publish.enabled:false}' == 'true' && "
            + "'${ioc.storage.service.type:disabled}' == 'jdbc'")
    public ArtifactPublishUseCase artifactPublishUseCase(
            CompletedSliceCatalog catalog,
            PublishLedger ledger,
            TransportRegistry transports,
            Retrier syncRetrier,
            DiagnosticSink diagnostics,
            IocProperties props,
            Clock clock) {
        return new ArtifactPublishService(
                catalog, ledger, transports, publishTargets(props), syncRetrier, diagnostics, clock);
    }

    @Bean
    @ConditionalOnExpression("'${ioc.runtime.mode}' == 'daemon' && "
            + "'${ioc.sync.enabled:false}' == 'true' && "
            + "'${ioc.sync.publish.enabled:false}' == 'true' && "
            + "'${ioc.storage.service.type:disabled}' == 'jdbc'")
    public SliceRetentionGuard publishLedgerSliceRetentionGuard(
            PublishLedger ledger,
            IocProperties props) {
        return new PublishLedgerSliceRetentionGuard(ledger, publishTargets(props));
    }

    @Bean
    @ConditionalOnExpression("'${ioc.runtime.mode}' == 'daemon' && "
            + "'${ioc.sync.enabled:false}' == 'true' && "
            + "'${ioc.sync.fetch.enabled:false}' == 'true' && "
            + "'${ioc.storage.service.type:disabled}' == 'jdbc'")
    public DaemonFetchScheduler daemonFetchScheduler(
            RemoteSourceMonitor monitor,
            ControlEventPublisher eventPublisher,
            TransportRegistry transports,
            SyncHealthState healthState,
            IocProperties props) {
        return new DaemonFetchScheduler(
                fetchSources(props), monitor, eventPublisher, transports, healthState, props.sync().fetch().interval());
    }

    @Bean
    @ConditionalOnExpression("'${ioc.runtime.mode}' == 'daemon' && "
            + "'${ioc.sync.enabled:false}' == 'true' && "
            + "'${ioc.sync.publish.enabled:false}' == 'true' && "
            + "'${ioc.storage.service.type:disabled}' == 'jdbc'")
    public DaemonPublishScheduler daemonPublishScheduler(
            ArtifactPublishUseCase useCase,
            TransportRegistry transports,
            SyncHealthState healthState,
            KeyedSerialExecutor syncPublishKeyedExecutor,
            IocProperties props) {
        return new DaemonPublishScheduler(
                publishTargets(props), useCase, transports, healthState,
                syncPublishKeyedExecutor, props.sync().publish().interval());
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnExpression("'${ioc.runtime.mode}' == 'daemon' && "
            + "'${ioc.sync.enabled:false}' == 'true' && "
            + "('${ioc.sync.fetch.enabled:false}' == 'true' || "
            + "'${ioc.sync.publish.enabled:false}' == 'true') && "
            + "'${ioc.storage.service.type:disabled}' == 'jdbc'")
    public KeyedSerialExecutor syncKeyedExecutor(IocProperties props) {
        long fetchEndpoints = fetchSources(props).stream()
                .map(RemoteFetchSource::endpoint)
                .distinct()
                .count();
        long publishEndpoints = publishTargets(props).stream()
                .map(PublishTarget::endpoint)
                .distinct()
                .count();
        int workers = Math.max(1, (int) Math.max(fetchEndpoints, publishEndpoints));
        return new BoundedKeyedSerialExecutor(
                Executors.newFixedThreadPool(workers, runnable -> {
                    Thread thread = new Thread(runnable, "ioc-sync-worker");
                    thread.setDaemon(false);
                    return thread;
                }),
                DEFAULT_SYNC_QUEUE_PER_ENDPOINT);
    }

    @Bean
    @ConditionalOnExpression("'${ioc.runtime.mode}' == 'daemon' && "
            + "'${ioc.sync.enabled:false}' == 'true' && "
            + "'${ioc.sync.publish.enabled:false}' == 'true' && "
            + "'${ioc.storage.service.type:disabled}' == 'jdbc'")
    public SliceCompletedPublishListener sliceCompletedPublishListener(
            ArtifactPublishUseCase useCase,
            KeyedSerialExecutor syncKeyedExecutor,
            ControlEventObserver observer,
            IocProperties props) {
        return new SliceCompletedPublishListener(
                useCase, syncKeyedExecutor, observer, publishTargets(props));
    }

    @Bean
    @ConditionalOnExpression("'${ioc.runtime.mode}' == 'daemon' && "
            + "'${ioc.sync.enabled:false}' == 'true' && "
            + "'${ioc.sync.fetch.enabled:false}' == 'true' && "
            + "'${ioc.storage.service.type:disabled}' == 'jdbc'")
    public RemoteChangeFetchListener remoteChangeFetchListener(
            RemoteFetchUseCase useCase,
            KeyedSerialExecutor syncKeyedExecutor,
            ControlEventObserver observer,
            SyncHealthState healthState) {
        return new RemoteChangeFetchListener(useCase, syncKeyedExecutor, observer, healthState);
    }

    @Bean("syncHealthIndicator")
    @ConditionalOnExpression("'${ioc.runtime.mode}' == 'daemon' && "
            + "'${ioc.sync.enabled:false}' == 'true' && "
            + "('${ioc.sync.fetch.enabled:false}' == 'true' || "
            + "'${ioc.sync.publish.enabled:false}' == 'true') && "
            + "'${ioc.storage.service.type:disabled}' == 'jdbc'")
    public HealthIndicator syncHealthIndicator(
            SyncHealthState state,
            PublishLedger ledger,
            CompletedSliceCatalog catalog,
            ObjectProvider<SliceRetentionGuard> retentionGuard,
            IocProperties props) {
        return new SyncHealthIndicator(
                fetchSources(props), publishTargets(props), state, ledger, catalog,
                retentionGuard.getIfAvailable(() -> descriptor -> true));
    }

    private void rejectUnsupportedTransports(IocProperties props, int supportedCount) {
        if (supportedCount == props.sync().endpoints().size()) {
            return;
        }
        String unsupported = props.sync().endpoints().stream()
                .filter(endpoint -> !"smb".equalsIgnoreCase(endpoint.transport()))
                .map(endpoint -> endpoint.transport())
                .distinct()
                .sorted()
                .reduce((left, right) -> left + ", " + right)
                .orElse("unknown");
        throw new IllegalArgumentException("Unsupported sync transport: " + unsupported);
    }

    private SmbEndpointSettings smbSettings(IocProperties.Sync.Endpoint endpoint) {
        IocProperties.Sync.Endpoint.Smb smb = endpoint.smb();
        char[] password = smb.password().toCharArray();
        try {
            return new SmbEndpointSettings(
                    endpoint.name(), smb.host(), smb.share(), smb.domain(), smb.username(), password,
                    smb.encrypt(), defaultDuration(smb.connectTimeout(), Duration.ofSeconds(10)),
                    defaultDuration(smb.readTimeout(), Duration.ofSeconds(30)),
                    defaultDuration(smb.idleTimeout(), Duration.ofMinutes(5)));
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private List<RemoteFetchSource> fetchSources(IocProperties props) {
        return props.sync().fetch().sources().stream()
                .map(source -> new RemoteFetchSource(
                        source.name(), source.endpoint(), source.remotePath(),
                        source.include(), source.exclude()))
                .toList();
    }

    private List<PublishTarget> publishTargets(IocProperties props) {
        return props.sync().publish().targets().stream()
                .map(target -> new PublishTarget(
                        target.name(), target.endpoint(), target.remotePath(), target.exportProfile()))
                .toList();
    }

    private Duration defaultDuration(Duration configured, Duration fallback) {
        return configured == null ? fallback : configured;
    }
}
