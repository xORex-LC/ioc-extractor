package com.iocextractor.application.sync;

import com.iocextractor.application.port.in.sync.FetchRemoteObjectsCommand;
import com.iocextractor.application.port.in.sync.RemoteFetchCommand;
import com.iocextractor.application.port.in.sync.RemoteFetchResult;
import com.iocextractor.application.port.in.sync.RemoteFetchUseCase;
import com.iocextractor.application.port.out.sync.FileTransport;
import com.iocextractor.application.port.out.sync.RemoteFetchLedger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Fetches remote regular files into the local ingestion inbox through an atomic landing protocol.
 */
public final class RemoteFetchService implements RemoteFetchUseCase {

    private static final String STAGING_DIR = ".sync-staging";
    private static final int MAX_INBOX_NAME_BYTES = 255;

    private final FileTransport transport;
    private final RemoteFetchLedger ledger;
    private final List<RemoteFetchSource> sources;
    private final Path inbox;
    private final Retrier retrier;
    private final Clock clock;
    private final SyncDiagnosticReporter diagnosticReporter;

    /** Creates a framework-free read-only remote fetch use case. */
    public RemoteFetchService(FileTransport transport,
                              RemoteFetchLedger ledger,
                              List<RemoteFetchSource> sources,
                              Path inbox,
                              Retrier retrier,
                              Clock clock,
                              SyncDiagnosticReporter diagnosticReporter) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        this.inbox = Objects.requireNonNull(inbox, "inbox").toAbsolutePath().normalize();
        this.retrier = Objects.requireNonNull(retrier, "retrier");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.diagnosticReporter = Objects.requireNonNull(diagnosticReporter, "diagnosticReporter");
    }

    @Override
    public RemoteFetchResult fetch(RemoteFetchCommand command) {
        Objects.requireNonNull(command, "command");
        FetchCounters counters = new FetchCounters();
        for (RemoteFetchSource source : RemoteFetchSources.selected(sources, command)) {
            RemoteFetchSources.SourceMatchers matchers = RemoteFetchSources.compileMatchers(source);
            int previousReviewCount = counters.reviewRequired;
            for (RemoteObject object : list(source)) {
                if (!matchers.matches(object)) {
                    counters.skipped++;
                    continue;
                }
                fetchOne(source, object, command.dryRun(), counters);
            }
            reportLegacyReview(source, counters.reviewRequired - previousReviewCount);
        }
        return counters.toResult();
    }

    @Override
    public RemoteFetchResult fetch(FetchRemoteObjectsCommand command) {
        Objects.requireNonNull(command, "command");
        RemoteFetchSource source = source(command);
        FetchCounters counters = new FetchCounters();
        for (RemoteObject object : command.objects()) {
            fetchOne(source, object, command.dryRun(), counters);
        }
        reportLegacyReview(source, counters.reviewRequired);
        return counters.toResult();
    }

    private void fetchOne(RemoteFetchSource source, RemoteObject object, boolean dryRun, FetchCounters counters) {
        RemoteObjectIdentity identity = object.identity(source.sourceId());
        if (ledger.find(identity)
                .filter(record -> record.status() == RemoteFetchStatus.FETCHED)
                .isPresent()) {
            counters.skipped++;
            return;
        }
        if (ledger.requiresLegacyReview(identity)) {
            counters.failed++;
            counters.reviewRequired++;
            return;
        }
        if (dryRun) {
            counters.skipped++;
            return;
        }
        Path staging = null;
        try {
            Path finalPath = finalPathFor(identity);
            Files.createDirectories(inbox);
            Path stagingDir = inbox.resolve(STAGING_DIR);
            Files.createDirectories(stagingDir);
            staging = Files.createTempFile(stagingDir, "fetch-", ".part");
            Path target = staging;
            retrier.run(() -> transport.get(source.endpoint(), object.path(), target));
            forceFile(staging);
            forceDirectory(stagingDir);
            Files.move(staging, finalPath, StandardCopyOption.ATOMIC_MOVE);
            forceDirectory(inbox);
            ledger.markFetched(identity, finalPath.toString(), clock.instant());
            counters.fetched++;
        } catch (RemoteTransportException failure) {
            cleanup(staging, failure);
            ledger.markFailed(identity, failure.getMessage(), clock.instant());
            diagnosticReporter.report(failure, source.endpoint(), object.path(), "fetch");
            counters.failed++;
        } catch (IOException | UncheckedIOException failure) {
            cleanup(staging, failure);
            ledger.markFailed(identity, failure.getMessage(), clock.instant());
            counters.failed++;
        } catch (RuntimeException failure) {
            cleanup(staging, failure);
            throw failure;
        }
    }

    private List<RemoteObject> list(RemoteFetchSource source) {
        try {
            return retrier.execute(() -> transport.list(source.endpoint(), source.remotePath()));
        } catch (RemoteTransportException failure) {
            diagnosticReporter.report(failure, source.endpoint(), source.remotePath(), "listing");
            throw failure;
        }
    }

    private RemoteFetchSource source(FetchRemoteObjectsCommand command) {
        return sources.stream()
                .filter(source -> source.sourceId().equals(command.source()))
                .filter(source -> source.endpoint().equals(command.endpoint()))
                .filter(source -> source.remotePath().equals(command.remotePath()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No sync fetch source matches detected batch: " + command.source()));
    }

    private void reportLegacyReview(RemoteFetchSource source, int count) {
        if (count > 0) {
            diagnosticReporter.reportLegacyReview(source.sourceId(), count);
        }
    }

    private Path finalPathFor(RemoteObjectIdentity identity) throws IOException {
        String leaf = RemoteFetchSources.leafName(identity.path());
        Path candidate = inbox.resolve(landingName(leaf, identity)).normalize();
        if (!inbox.equals(candidate.getParent())) {
            throw new IllegalArgumentException("remote object leaf escapes inbox");
        }
        if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Remote fetch landing name is occupied");
        }
        return candidate;
    }

    private String landingName(String leaf, RemoteObjectIdentity identity) {
        String digest = stableDigest(identity).substring(0, 32);
        int dot = leaf.lastIndexOf('.');
        String stem = dot <= 0 ? leaf : leaf.substring(0, dot);
        String extension = dot <= 0 ? "" : leaf.substring(dot);
        String suffixed = stem + "__" + digest + extension;
        if (suffixed.getBytes(StandardCharsets.UTF_8).length <= MAX_INBOX_NAME_BYTES) {
            return suffixed;
        }
        // SMB can return names that fit the filesystem limit only before suffixing.
        String shortName = (leaf.startsWith(".") ? "." : "") + digest;
        return (shortName + extension).getBytes(StandardCharsets.UTF_8).length <= MAX_INBOX_NAME_BYTES
                ? shortName + extension : shortName;
    }

    private String stableDigest(RemoteObjectIdentity identity) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(identity.sourceId().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(identity.path().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Long.toString(identity.size()).getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) 0);
            digest.update(identity.modifiedAt().toString().getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private void forceFile(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private void forceDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException ignored) {
            // Some filesystems/JDKs do not allow opening directories. Atomic move still keeps
            // visibility correct; this is a durability best-effort seam for supported platforms.
        }
    }

    private void cleanup(Path staging, Throwable originalFailure) {
        if (staging == null) {
            return;
        }
        try {
            Files.deleteIfExists(staging);
        } catch (IOException e) {
            originalFailure.addSuppressed(new UncheckedIOException(
                    "Failed to cleanup remote fetch staging file", e));
        }
    }

    private static final class FetchCounters {
        private int fetched;
        private int skipped;
        private int failed;
        private int reviewRequired;

        private RemoteFetchResult toResult() {
            return new RemoteFetchResult(fetched, skipped, failed);
        }
    }

}
