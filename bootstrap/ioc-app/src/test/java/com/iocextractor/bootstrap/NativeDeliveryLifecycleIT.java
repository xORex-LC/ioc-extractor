package com.iocextractor.bootstrap;

import com.iocextractor.adapter.in.ingest.FileSourceHasher;
import com.iocextractor.adapter.out.transport.smb.SmbFileTransport;
import com.iocextractor.application.artifact.lifecycle.ConfirmationReceiptContext;
import com.iocextractor.application.artifact.lifecycle.ConfirmationReceiptId;
import com.iocextractor.application.artifact.lifecycle.ConfirmationReceiptReplayCommand;
import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.artifact.lifecycle.LifecycleWriteContext;
import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.export.ExportRunStatus;
import com.iocextractor.application.observation.ObservationOrigin;
import com.iocextractor.application.pipeline.CompletionStatus;
import com.iocextractor.application.port.in.ExtractIocsUseCase;
import com.iocextractor.application.port.in.ExtractionCommand;
import com.iocextractor.application.port.in.artifact.lifecycle.ReplayConfirmationReceiptUseCase;
import com.iocextractor.application.port.in.export.ExportArtifactsCommand;
import com.iocextractor.application.port.in.export.ExportArtifactsUseCase;
import com.iocextractor.application.port.in.sync.ArtifactPublishCommand;
import com.iocextractor.application.port.in.sync.ArtifactPublishUseCase;
import com.iocextractor.application.port.in.sync.RemoteFetchCommand;
import com.iocextractor.application.port.in.sync.RemoteFetchUseCase;
import com.iocextractor.application.port.out.artifact.lifecycle.CanonicalObservationStore;
import com.iocextractor.application.port.out.artifact.lifecycle.ConfirmationReceiptStore;
import com.iocextractor.application.port.out.observation.ObservationRegistrationStore;
import com.iocextractor.application.port.out.sync.PublishLedger;
import com.iocextractor.application.sync.PublishAtomicallyRequest;
import com.iocextractor.application.sync.PublishReceipt;
import com.iocextractor.application.sync.PublishStatus;
import com.iocextractor.application.sync.RemoteErrorKind;
import com.iocextractor.application.sync.RemoteObject;
import com.iocextractor.application.sync.RemoteTransportException;
import com.iocextractor.application.tck.junit.EndToEndTest;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Native fetch/processing/export wiring with real storage, receipt recovery and TTL boundaries. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"golden", "customer-routes", "native-delivery"})
@EndToEndTest
@Timeout(90)
class NativeDeliveryLifecycleIT {
    private static final String PROFILE = "reputation-lists";
    private static final Instant START = Instant.parse("2026-10-13T00:00:00Z");
    @TempDir static Path root;

    @DynamicPropertySource
    static void paths(DynamicPropertyRegistry registry) {
        registry.add("native.delivery.root", () -> root.toAbsolutePath().toString());
        registry.add("golden.output-dir", () -> root.toAbsolutePath().toString());
    }

    @Autowired RemoteFetchUseCase fetch;
    @Autowired ExtractIocsUseCase extract;
    @Autowired ExportArtifactsUseCase export;
    @Autowired ArtifactPublishUseCase publish;
    @Autowired PublishLedger deliveries;
    @Autowired ConfirmationReceiptStore receipts;
    @Autowired ReplayConfirmationReceiptUseCase replay;
    @Autowired CanonicalObservationStore observations;
    @Autowired ObservationRegistrationStore registrations;
    @Autowired ProcessingPolicyIdentity policy;
    @TestBean(name = "clock", enforceOverride = true) Clock clock;
    @TestBean(name = "smbFileTransport", methodName = "transport", enforceOverride = true) SmbFileTransport wire;
    @Autowired @Qualifier("dataframeStorageDataSource") HikariDataSource dataframe;

    @Test
    void sourceToPublicationRecoversWithoutSourceAndReincarnatesOnlyAfterExpiry() throws Exception {
        Path remoteSource = root.resolve("remote-source.html");
        Files.writeString(remoteSource, """
                <html><head><meta charset="utf-8"></head><body>
                <p>БИБ-001</p><p>hxxps[:]//best-malware[.]com/one?x=1</p>
                <p>https://best-malware.com:8443/two?x=2</p>
                <p>10.93.12.187:9090/endpoint</p>
                </body></html>
                """);
        var remote = new RemoteObject("incoming/source.html", Files.size(remoteSource), START);
        when(wire.list("fixture", "incoming")).thenReturn(List.of(remote));
        when(wire.stat("fixture", remote.path())).thenReturn(Optional.of(remote));
        doAnswer(call -> {
            Files.copy(remoteSource, call.getArgument(2, Path.class), StandardCopyOption.REPLACE_EXISTING);
            return null;
        }).when(wire).get(eq("fixture"), eq(remote.path()), any(Path.class));
        var lostReply = new AtomicBoolean(true);
        when(wire.publishAtomically(any())).thenAnswer(call -> {
            var request = call.getArgument(0, PublishAtomicallyRequest.class);
            Path destination = root.resolve("remote").resolve(request.remotePath());
            if (Files.notExists(destination)) {
                Files.createDirectories(destination);
                try (var files = Files.list(request.localDirectory())) {
                    for (var file : files.toList()) { Files.copy(file, destination.resolve(file.getFileName())); }
                }
            }
            if (lostReply.getAndSet(false)) {
                throw new RemoteTransportException(RemoteErrorKind.TRANSIENT, "Committed remote slice; reply lost");
            }
            return new PublishReceipt(request.remotePath(), "fixture-marker-verified");
        });

        assertThat(fetch.fetch(new RemoteFetchCommand(false)).fetched()).isOne();
        assertThat(fetch.fetch(new RemoteFetchCommand(false)).fetched()).isZero();
        Path source;
        try (var files = Files.list(root.resolve("inbox"))) {
            source = files.filter(Files::isRegularFile).findFirst().orElseThrow();
        }
        String sourceKey = new FileSourceHasher().sha256(source).value();
        var result = extract.extract(new ExtractionCommand("native-first", source, false));
        assertThat(result.completionStatus()).isEqualTo(CompletionStatus.COMPLETED);
        assertThat(result.extracted()).isEqualTo(3);
        var first = activeMasks();
        assertThat(first).singleElement().satisfies(record -> {
            assertThat(record.mask()).isEqualTo("best-malware.com");
            assertThat(record.source()).isEqualTo("БИБ-001");
        });
        assertThat(number("SELECT COUNT(*) FROM ip_list WHERE ip='10.93.12.187'")).isOne();
        assertThat(number("SELECT SUM(occurrences) FROM masks_sources")).isOne();
        assertThat(receipts.findComplete(sourceKey, policy.value(), EffectiveTime.at(START)).orElseThrow().artifacts())
                .hasSize(5);
        var firstExport = export.export(new ExportArtifactsCommand(PROFILE));
        assertThat(firstExport.status()).isEqualTo(ExportRunStatus.COMPLETED);
        Path firstCsv = root.resolve("export").resolve(PROFILE).resolve(firstExport.sliceName()).resolve("masks.csv");
        byte[] immutable = Files.readAllBytes(firstCsv);
        var selection = new ArtifactPublishCommand(Optional.of(PROFILE), false);
        assertThat(publish.reconcile(selection).pending()).isOne();
        assertThat(publish.publish(selection).failed()).isOne();
        assertThat(deliveries.findAll()).singleElement().extracting(record -> record.status()).isEqualTo(PublishStatus.FAILED);
        assertThat(publish.publish(selection).succeeded()).isOne();
        assertThat(publish.publish(selection).attempted()).isZero();
        assertThat(deliveries.findAll()).singleElement().satisfies(record -> {
            assertThat(record.status()).isEqualTo(PublishStatus.SUCCEEDED);
            Path delivered = root.resolve("remote").resolve(record.remotePath());
            assertThat(Files.readAllBytes(delivered.resolve("masks.csv"))).isEqualTo(immutable);
            assertThat(Files.readAllBytes(delivered.resolve("_SUCCESS")))
                    .isEqualTo(Files.readAllBytes(firstCsv.getParent().resolve("_SUCCESS")));
        });

        // Receipt recovery cannot read or reprocess either original source file.
        Files.delete(source);
        Files.delete(remoteSource);
        replay(sourceKey, "native-renewal");
        assertThat(activeMasks().getFirst().lifecycleId()).isEqualTo(first.getFirst().lifecycleId());
        assertThat(export.export(new ExportArtifactsCommand(PROFILE)).status()).isEqualTo(ExportRunStatus.SKIPPED);
        assertThat(number("SELECT SUM(occurrences) FROM masks_sources")).isEqualTo(2);

        ((MutableClock) clock).current = START.plus(Duration.ofHours(1));
        assertThat(activeMasks()).isEmpty();
        replay(sourceKey, "native-after-expiry");
        var reincarnated = activeMasks().getFirst();
        assertThat(reincarnated.lifecycleId()).isNotEqualTo(first.getFirst().lifecycleId());
        assertThat(reincarnated.id()).isGreaterThan(first.getFirst().id());
        var newExport = export.export(new ExportArtifactsCommand(PROFILE));
        assertThat(newExport.status()).isEqualTo(ExportRunStatus.COMPLETED);
        assertThat(newExport.sliceName()).isNotEqualTo(firstExport.sliceName());
        Path newCsv = firstCsv.getParent().getParent().resolve(newExport.sliceName()).resolve("masks.csv");
        assertThat(Files.readAllBytes(newCsv)).isEqualTo(immutable);
        assertThat(Files.readAllBytes(firstCsv)).isEqualTo(immutable);
        assertThat(publish.reconcile(selection).pending()).isOne();
        assertThat(publish.publish(selection).succeeded()).isOne();
        assertThat(deliveries.findAll()).hasSize(2).allSatisfy(record -> assertThat(record.status()).isEqualTo(PublishStatus.SUCCEEDED));
        assertThat(number("SELECT COUNT(*) FROM canonical_observation_commit")).isEqualTo(15);
    }

    private void replay(String sourceKey, String id) {
        var observation = new ObservationId(id);
        var registration = registrations.registerNew(observation, ObservationOrigin.ONESHOT);
        var receipt = new ConfirmationReceiptContext(new ConfirmationReceiptId("receipt:" + id), policy.value(), 5, Duration.ofDays(30));
        var command = new ConfirmationReceiptReplayCommand(new LifecycleWriteContext(observation, sourceKey, receipt, registration));
        try {
            assertThat(replay.replay(command)).isPresent();
            var retry = replay.replay(command).orElseThrow();
            assertThat(retry.artifacts().values()).allSatisfy(outcome -> assertThat(outcome.replayed()).isTrue());
            observations.markTerminal(observation, EffectiveTime.at(clock.instant()), Duration.ofDays(30));
        } finally { registrations.markTerminal(observation, registration.namespaceId()); }
    }

    private long number(String sql) throws Exception {
        try (var connection = dataframe.getConnection(); var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getLong(1);
        }
    }

    private List<Mask> activeMasks() throws Exception {
        var result = new java.util.ArrayList<Mask>();
        try (var connection = dataframe.getConnection();
             var statement = connection.prepareStatement("SELECT id,_lifecycle_id,mask,source FROM masks WHERE _valid_until_epoch_ms>?")) {
            statement.setLong(1, clock.instant().toEpochMilli());
            try (var rows = statement.executeQuery()) {
                while (rows.next()) { result.add(new Mask(rows.getLong(1), rows.getLong(2), rows.getString(3), rows.getString(4))); }
            }
        }
        return result;
    }

    private record Mask(long id, long lifecycleId, String mask, String source) { }

    static MutableClock clock() { return new MutableClock(); }
    static SmbFileTransport transport() { return mock(SmbFileTransport.class); }

    static final class MutableClock extends Clock {
        private Instant current = START;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(current, zone); }
        @Override public Instant instant() { return current; }
    }
}
