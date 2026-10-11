package com.iocextractor.adapter.out.store.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

/** Two bounded passes over one sealed native snapshot; no materialized change list or table-wide diff. */
final class JdbcPublicationTransfer {
    private final JdbcTypedPublicationStore store;
    private final int byteLimit;
    JdbcPublicationTransfer(JdbcTypedPublicationStore store) { this(store, 1024 * 1024); }
    JdbcPublicationTransfer(JdbcTypedPublicationStore store, int byteLimit) {
        this.store = store; this.byteLimit = byteLimit;
    }

    record Description(int chunks, int rows, long minimumDeadline) { }

    Description describe(Connection working, boolean publicationTime, long preparedUtc, long minimumDeadline)
            throws SQLException {
        var counts = new int[2];
        long[] deadline = {minimumDeadline};
        walk(working, publicationTime, effect -> {
            if (sameLifecycle(effect)) {
                long oldDeadline = ((Number) effect.oldValue("_valid_until_epoch_ms")).longValue();
                if (oldDeadline > preparedUtc) { deadline[0] = Math.min(deadline[0], oldDeadline); }
            }
        }, chunk -> {
            counts[0] = Math.incrementExact(counts[0]);
            counts[1] = Math.addExact(counts[1], chunk.size());
        });
        return new Description(counts[0], counts[1], deadline[0]);
    }

    JdbcPublicationProtocol.Attempt stage(Connection working, String identity, String pins,
                                          Map<String, Long> dependencies, Description description,
                                          boolean publicationTime, Writer writer) throws SQLException {
        String payloadScope = dependencies.keySet().stream().sorted().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Publication needs an affected scope"));
        var manifest = new JdbcPublicationProtocol.Manifest(description.chunks(), description.rows(),
                description.minimumDeadline(), dependencies);
        JdbcPublicationProtocol.Attempt attempt = writer.begin(identity, pins, manifest);
        int[] ordinal = {0};
        walk(working, publicationTime, ignored -> { },
                chunk -> writer.append(attempt, payloadScope, ordinal[0]++, chunk));
        if (ordinal[0] != description.chunks()) { throw new SQLException("Private publication snapshot changed"); }
        writer.seal(attempt);
        return attempt;
    }

    private boolean sameLifecycle(JdbcPrivateChanges.Effect effect) {
        if (effect.deleted() || effect.previous() == null || !store.artifacts().contains(effect.table().name())) {
            return false;
        }
        return java.util.Objects.equals(effect.value("_lifecycle_id"), effect.oldValue("_lifecycle_id"));
    }

    private void walk(Connection working, boolean publicationTime, JdbcPrivateChanges.EffectConsumer observer,
                      ChunkConsumer consumer) throws SQLException {
        var accumulator = new Accumulator(publicationTime, observer, consumer);
        for (var table : store.tables()) {
            long after = 0;
            while (true) {
                long next = JdbcPrivateChanges.read(working, table, after, accumulator::add);
                if (next == after) { break; }
                after = next;
            }
        }
        accumulator.finish();
    }

    private final class Accumulator {
        private final boolean publicationTime;
        private final JdbcPrivateChanges.EffectConsumer observer;
        private final ChunkConsumer consumer;
        private JdbcTypedPublicationChunk chunk = new JdbcTypedPublicationChunk(store.artifacts(), byteLimit);

        Accumulator(boolean publicationTime, JdbcPrivateChanges.EffectConsumer observer, ChunkConsumer consumer) {
            this.publicationTime = publicationTime; this.observer = observer; this.consumer = consumer;
        }

        void add(JdbcPrivateChanges.Effect effect) throws SQLException {
            if (Thread.currentThread().isInterrupted()) { throw new SQLException("Typed publication transfer interrupted"); }
            observer.accept(effect);
            if (!chunk.add(effect, publicationTime)) {
                consumer.accept(chunk);
                chunk = new JdbcTypedPublicationChunk(store.artifacts(), byteLimit);
                if (!chunk.add(effect, publicationTime)) { throw new SQLException("Empty chunk refused a bounded effect"); }
            }
        }

        void finish() throws SQLException { if (!chunk.isEmpty()) { consumer.accept(chunk); } }
    }

    @FunctionalInterface private interface ChunkConsumer { void accept(JdbcTypedPublicationChunk chunk) throws SQLException; }
    interface Writer {
        JdbcPublicationProtocol.Attempt begin(String identity, String pins, JdbcPublicationProtocol.Manifest manifest)
                throws SQLException;
        void append(JdbcPublicationProtocol.Attempt attempt, String scope, int ordinal, JdbcTypedPublicationChunk chunk)
                throws SQLException;
        void seal(JdbcPublicationProtocol.Attempt attempt) throws SQLException;
    }
}
