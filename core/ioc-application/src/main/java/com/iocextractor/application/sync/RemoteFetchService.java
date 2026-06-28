package com.iocextractor.application.sync;

import com.iocextractor.application.port.in.sync.RemoteFetchCommand;
import com.iocextractor.application.port.in.sync.RemoteFetchResult;
import com.iocextractor.application.port.in.sync.RemoteFetchUseCase;
import com.iocextractor.application.port.out.sync.FileTransport;
import com.iocextractor.application.port.out.sync.RemoteFetchLedger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
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

    private final FileTransport transport;
    private final RemoteFetchLedger ledger;
    private final List<RemoteFetchSource> sources;
    private final Path inbox;
    private final Retrier retrier;
    private final Clock clock;

    /** Creates a framework-free read-only remote fetch use case. */
    public RemoteFetchService(FileTransport transport,
                              RemoteFetchLedger ledger,
                              List<RemoteFetchSource> sources,
                              Path inbox,
                              Retrier retrier,
                              Clock clock) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        this.inbox = Objects.requireNonNull(inbox, "inbox").toAbsolutePath().normalize();
        this.retrier = Objects.requireNonNull(retrier, "retrier");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public RemoteFetchResult fetch(RemoteFetchCommand command) {
        Objects.requireNonNull(command, "command");
        FetchCounters counters = new FetchCounters();
        for (RemoteFetchSource source : selectedSources(command)) {
            for (RemoteObject object : transport.list(source.endpoint(), source.remotePath())) {
                if (!matches(source, object)) {
                    counters.skipped++;
                    continue;
                }
                fetchOne(source, object, command.dryRun(), counters);
            }
        }
        return counters.toResult();
    }

    private List<RemoteFetchSource> selectedSources(RemoteFetchCommand command) {
        List<RemoteFetchSource> matches = sources.stream()
                .filter(source -> command.source()
                        .map(selected -> source.sourceId().equals(selected))
                        .orElse(true))
                .filter(source -> command.endpoint()
                        .map(selected -> source.endpoint().equals(selected))
                        .orElse(true))
                .toList();
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("No sync fetch source matches selection");
        }
        return matches;
    }

    private void fetchOne(RemoteFetchSource source, RemoteObject object, boolean dryRun, FetchCounters counters) {
        RemoteObjectIdentity identity = object.identity();
        if (ledger.find(identity)
                .filter(record -> record.status() == RemoteFetchStatus.FETCHED)
                .isPresent()) {
            counters.skipped++;
            return;
        }
        if (dryRun) {
            counters.skipped++;
            return;
        }
        Path staging = null;
        try {
            Path finalPath = finalPathFor(object);
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
            cleanup(staging);
            ledger.markFailed(identity, failure.getMessage(), clock.instant());
            counters.failed++;
        } catch (IOException | UncheckedIOException failure) {
            cleanup(staging);
            ledger.markFailed(identity, failure.getMessage(), clock.instant());
            counters.failed++;
        } catch (RuntimeException failure) {
            cleanup(staging);
            ledger.markFailed(identity, failure.getMessage(), clock.instant());
            counters.failed++;
        }
    }

    private boolean matches(RemoteFetchSource source, RemoteObject object) {
        String leaf = leafName(object.path());
        boolean included = source.include().isEmpty()
                || source.include().stream().anyMatch(pattern -> glob(pattern).matches(Path.of(leaf)));
        boolean excluded = source.exclude().stream().anyMatch(pattern -> glob(pattern).matches(Path.of(leaf)));
        return included && !excluded;
    }

    private PathMatcher glob(String pattern) {
        return FileSystems.getDefault().getPathMatcher("glob:" + pattern);
    }

    private Path finalPathFor(RemoteObject object) {
        String leaf = leafName(object.path());
        Path candidate = inbox.resolve(leaf).normalize();
        if (!candidate.getParent().equals(inbox)) {
            throw new IllegalArgumentException("remote object leaf escapes inbox: " + object.path());
        }
        if (!Files.exists(candidate)) {
            return candidate;
        }
        String suffixed = suffix(leaf, object.identity());
        Path suffixedCandidate = inbox.resolve(suffixed).normalize();
        if (!suffixedCandidate.getParent().equals(inbox)) {
            throw new IllegalArgumentException("remote object suffix escapes inbox: " + object.path());
        }
        if (Files.exists(suffixedCandidate)) {
            throw new IllegalStateException("Inbox file already exists for remote identity: " + suffixedCandidate);
        }
        return suffixedCandidate;
    }

    private String leafName(String remotePath) {
        int slash = Math.max(remotePath.lastIndexOf('/'), remotePath.lastIndexOf('\\'));
        String leaf = slash >= 0 ? remotePath.substring(slash + 1) : remotePath;
        if (leaf.isBlank() || leaf.equals(".") || leaf.equals("..")
                || leaf.contains("/") || leaf.contains("\\")) {
            throw new IllegalArgumentException("remote path must end with one safe file name: " + remotePath);
        }
        return leaf;
    }

    private String suffix(String leaf, RemoteObjectIdentity identity) {
        String digest = stableDigest(identity).substring(0, 12);
        int dot = leaf.lastIndexOf('.');
        if (dot <= 0) {
            return leaf + "__" + digest;
        }
        return leaf.substring(0, dot) + "__" + digest + leaf.substring(dot);
    }

    private String stableDigest(RemoteObjectIdentity identity) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(identity.path().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Long.toString(identity.size()).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            digest.update((byte) 0);
            digest.update(identity.modifiedAt().toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
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

    private void cleanup(Path staging) {
        if (staging == null) {
            return;
        }
        try {
            Files.deleteIfExists(staging);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to cleanup remote fetch staging file", e);
        }
    }

    private static final class FetchCounters {
        private int fetched;
        private int skipped;
        private int failed;

        private RemoteFetchResult toResult() {
            return new RemoteFetchResult(fetched, skipped, failed);
        }
    }
}
