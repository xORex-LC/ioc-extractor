package com.iocextractor.application.sync;

import com.iocextractor.application.port.in.sync.ArtifactPublishCommand;
import com.iocextractor.application.port.in.sync.ArtifactPublishResult;
import com.iocextractor.application.port.in.sync.PublishCompletedSliceCommand;
import com.iocextractor.application.port.in.sync.RemoteFetchCommand;
import com.iocextractor.application.port.in.sync.RemoteFetchResult;
import com.iocextractor.platform.events.ControlEventMetadata;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SyncValueObjectsTest {

    private static final Instant NOW = Instant.parse("2026-06-28T00:00:00Z");
    private static final String SHA256 = "a".repeat(64);

    @Test
    void remoteObjectIdentityUsesPathSizeAndMtime() {
        Instant modifiedAt = Instant.parse("2026-06-28T00:00:00Z");
        RemoteObject object = new RemoteObject("/incoming/a.htm", 42, modifiedAt);

        assertThat(object.identity())
                .isEqualTo(new RemoteObjectIdentity("/incoming/a.htm", 42, modifiedAt));
    }

    @Test
    void publishRequestRejectsUnsafeMarkerNames() {
        assertThatThrownBy(() -> new PublishAtomicallyRequest(
                "dist", "/out", Path.of("slice"), "../_SUCCESS"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("safe path segment");
    }

    @Test
    void retryPolicyRejectsInvalidNumbers() {
        assertThatThrownBy(() -> new RetryPolicy(0, Duration.ofSeconds(1), 2.0d,
                Duration.ofSeconds(5), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxAttempts");
        assertThatThrownBy(() -> new RetryPolicy(1, Duration.ofSeconds(2), 0.5d,
                Duration.ofSeconds(5), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("multiplier");
        assertThatThrownBy(() -> new RetryPolicy(1, Duration.ofSeconds(5), 1.0d,
                Duration.ofSeconds(1), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxBackoff");
    }

    @Test
    void watchTargetCarriesOnlyTransportLocationFromFetchSource() {
        var source = new RemoteFetchSource(
                "incoming", "primary", "/send", List.of("*.csv"), List.of("*.part"));

        assertThat(RemoteWatchTarget.from(source))
                .isEqualTo(new RemoteWatchTarget("incoming", "primary", "/send"));
    }

    @Test
    void publishTargetBuildsOnlySafeRemoteSlicePaths() {
        assertThat(new PublishTarget("target", "endpoint", "/out", "profile")
                .sliceRemotePath("slice-1"))
                .isEqualTo("/out/slice-1");
        assertThat(new PublishTarget("target", "endpoint", "/out/", "profile")
                .sliceRemotePath("slice-1"))
                .isEqualTo("/out/slice-1");

        for (String sliceName : List.of("nested/slice", "nested\\slice", ".", "..")) {
            assertThatThrownBy(() -> new PublishTarget(
                    "target", "endpoint", "/out", "profile").sliceRemotePath(sliceName))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("safe path segment");
        }
        assertThatThrownBy(() -> new PublishTarget(" ", "endpoint", "/out", "profile"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targetId");
    }

    @Test
    void atomicPublishRequestRejectsEveryUnsafeOrMissingTransportFact() {
        for (String marker : List.of("nested/_SUCCESS", "nested\\_SUCCESS", ".", "..")) {
            assertThatThrownBy(() -> new PublishAtomicallyRequest(
                    "dist", "/out", Path.of("slice"), marker))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("safe path segment");
        }
        assertThatThrownBy(() -> new PublishAtomicallyRequest(
                " ", "/out", Path.of("slice"), "_SUCCESS"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endpoint");
        assertThatThrownBy(() -> new PublishAtomicallyRequest(
                "dist", null, Path.of("slice"), "_SUCCESS"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("remotePath");
        assertThatThrownBy(() -> new PublishAtomicallyRequest(
                "dist", "/out", null, "_SUCCESS"))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("localDirectory");
    }

    @Test
    void retryPolicyBoundsBackoffAndRejectsNonPositiveDurations() {
        RetryPolicy policy = new RetryPolicy(
                4, Duration.ofSeconds(2), 3.0d, Duration.ofSeconds(10), false);

        assertThat(policy.delayAfterAttempt(1)).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.delayAfterAttempt(2)).isEqualTo(Duration.ofSeconds(6));
        assertThat(policy.delayAfterAttempt(3)).isEqualTo(Duration.ofSeconds(10));
        assertThatThrownBy(() -> policy.delayAfterAttempt(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> new RetryPolicy(
                1, Duration.ZERO, 1.0d, Duration.ofSeconds(1), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("backoff");
        assertThatThrownBy(() -> new RetryPolicy(
                1, Duration.ofSeconds(1), 1.0d, Duration.ofSeconds(-1), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxBackoff");
    }

    @Test
    void optionalSyncCommandsNormalizeNullSelectionsAndRejectBlankOnes() {
        assertThat(new ArtifactPublishCommand(null, null, null, true))
                .satisfies(command -> {
                    assertThat(command.profile()).isEmpty();
                    assertThat(command.target()).isEmpty();
                    assertThat(command.endpoint()).isEmpty();
                });
        assertThat(new RemoteFetchCommand(null, null, true))
                .satisfies(command -> {
                    assertThat(command.source()).isEmpty();
                    assertThat(command.endpoint()).isEmpty();
                });

        assertThatThrownBy(() -> new ArtifactPublishCommand(
                Optional.of(" "), Optional.empty(), Optional.empty(), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("profile");
        assertThatThrownBy(() -> new ArtifactPublishCommand(
                Optional.empty(), Optional.of(" "), Optional.empty(), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("target");
        assertThatThrownBy(() -> new ArtifactPublishCommand(
                Optional.empty(), Optional.empty(), Optional.of(" "), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endpoint");
        assertThatThrownBy(() -> new RemoteFetchCommand(
                Optional.of(" "), Optional.empty(), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source");
        assertThatThrownBy(() -> new RemoteFetchCommand(
                Optional.empty(), Optional.of(" "), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endpoint");
    }

    @Test
    void completedSliceCommandPreservesNullableRoutingAndCausation() {
        PublishCompletedSliceCommand command = new PublishCompletedSliceCommand(
                "profile", "slice-id", "slice-name",
                (String) null, (String) null, "corr-1", null);

        assertThat(command.target()).isEmpty();
        assertThat(command.endpoint()).isEmpty();
        assertThat(command.causationId()).isNull();
        assertThatThrownBy(() -> new PublishCompletedSliceCommand(
                "profile", "slice-id", "slice-name",
                Optional.of(" "), Optional.empty(), "corr-1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("target");
        assertThatThrownBy(() -> new PublishCompletedSliceCommand(
                "profile", "slice-id", "slice-name",
                Optional.empty(), Optional.of(" "), "corr-1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endpoint");
        assertThatThrownBy(() -> new PublishCompletedSliceCommand(
                "profile", "slice-id", "slice-name",
                Optional.empty(), Optional.empty(), "corr-1", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("causationId");
        assertThatThrownBy(() -> new PublishCompletedSliceCommand(
                null, "slice-id", "slice-name",
                Optional.empty(), Optional.empty(), "corr-1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("profile");
    }

    @Test
    void remoteObjectAndIdentityRejectUnstableIdentityFacts() {
        assertThatThrownBy(() -> new RemoteObject(" ", 0, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("path");
        assertThatThrownBy(() -> new RemoteObject("/a", -1, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("size");
        assertThatThrownBy(() -> new RemoteObject("/a", 0, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("modifiedAt");
        assertThatThrownBy(() -> new RemoteObjectIdentity(null, 0, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("path");
        assertThatThrownBy(() -> new RemoteObjectIdentity("/a", -1, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("size");
        assertThatThrownBy(() -> new RemoteObjectIdentity("/a", 0, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("modifiedAt");
    }

    @Test
    void fetchedLedgerRecordRequiresCompletedLocalEvidence() {
        RemoteObjectIdentity identity = new RemoteObjectIdentity("/a", 1, NOW);
        assertThat(new RemoteFetchRecord(
                identity, RemoteFetchStatus.FAILED, null, 0, "failed", null, NOW))
                .extracting(RemoteFetchRecord::status)
                .isEqualTo(RemoteFetchStatus.FAILED);
        assertThatThrownBy(() -> new RemoteFetchRecord(
                identity, RemoteFetchStatus.FAILED, null, -1, null, null, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attempts");
        assertThatThrownBy(() -> new RemoteFetchRecord(
                identity, RemoteFetchStatus.FETCHED, null, 1, null, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("localPath");
        assertThatThrownBy(() -> new RemoteFetchRecord(
                identity, RemoteFetchStatus.FETCHED, " ", 1, null, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("localPath");
        assertThatThrownBy(() -> new RemoteFetchRecord(
                identity, RemoteFetchStatus.FETCHED, "/local/a", 1, null, null, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fetchedAt");
    }

    @Test
    void remoteChangeBatchRequiresAddressableNonEmptyWork() {
        ControlEventMetadata metadata = ControlEventMetadata.withoutCausation(
                "event-1", RemoteChangeBatchDetected.EVENT_TYPE,
                RemoteChangeBatchDetected.EVENT_VERSION, NOW, "corr-1");
        RemoteObject object = new RemoteObject("/a", 1, NOW);

        assertThat(new RemoteChangeBatchDetected(
                metadata, "source", "endpoint", "/incoming", List.of(object)).objects())
                .containsExactly(object);
        assertThatThrownBy(() -> new RemoteChangeBatchDetected(
                metadata, " ", "endpoint", "/incoming", List.of(object)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceId");
        assertThatThrownBy(() -> new RemoteChangeBatchDetected(
                metadata, "source", "endpoint", "/incoming", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("objects");
    }

    @Test
    void publishLedgerRecordsRequireStableIdentityAndVerification() {
        PublishRecord pending = PublishRecord.pending(
                "slice-id", "target", "profile", "slice-name", SHA256,
                "endpoint", "/out/slice-name", NOW);
        assertThat(pending.status()).isEqualTo(PublishStatus.PENDING);
        assertThat(pending.attempts()).isZero();

        assertThatThrownBy(() -> publishRecord("not-a-hash", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SHA-256");
        assertThatThrownBy(() -> publishRecord(SHA256, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attempts");
        assertThatThrownBy(() -> new PublishReceipt(null, "etag"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("remotePath");
        assertThatThrownBy(() -> new PublishReceipt("/out", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("verification");
    }

    @Test
    void operatorResultCountersRejectEachNegativeDimension() {
        for (int[] counters : List.of(
                new int[] {-1, 0, 0},
                new int[] {0, -1, 0},
                new int[] {0, 0, -1})) {
            assertThatThrownBy(() -> new RemoteFetchResult(
                    counters[0], counters[1], counters[2]))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("fetch counters");
        }
        for (int[] counters : List.of(
                new int[] {-1, 0, 0, 0},
                new int[] {0, -1, 0, 0},
                new int[] {0, 0, -1, 0},
                new int[] {0, 0, 0, -1})) {
            assertThatThrownBy(() -> new ArtifactPublishResult(
                    counters[0], counters[1], counters[2], counters[3]))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("publish counters");
        }
    }

    @Test
    void sourceSelectionAndMatchersUseLeafNamesAndBothFilters() {
        RemoteFetchSource csv = new RemoteFetchSource(
                "csv", "primary", "/incoming", List.of("*.csv"), List.of("secret*"));
        RemoteFetchSource any = new RemoteFetchSource(
                "any", "secondary", "/incoming", List.of(), List.of("*.tmp"));

        assertThat(RemoteFetchSources.selected(
                List.of(csv, any), new RemoteFetchCommand(false)))
                .containsExactly(csv, any);
        assertThat(RemoteFetchSources.selected(
                List.of(csv, any), new RemoteFetchCommand(
                        Optional.of("csv"), Optional.of("primary"), false)))
                .containsExactly(csv);
        assertThatThrownBy(() -> RemoteFetchSources.selected(
                List.of(csv), new RemoteFetchCommand(
                        Optional.of("csv"), Optional.of("secondary"), false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("matches selection");

        RemoteFetchSources.SourceMatchers csvMatchers = RemoteFetchSources.compileMatchers(csv);
        assertThat(csvMatchers.matches(new RemoteObject("/incoming/good.csv", 1, NOW))).isTrue();
        assertThat(csvMatchers.matches(new RemoteObject("/incoming/good.txt", 1, NOW))).isFalse();
        assertThat(csvMatchers.matches(new RemoteObject("/incoming/secret.csv", 1, NOW))).isFalse();
        RemoteFetchSources.SourceMatchers anyMatchers = RemoteFetchSources.compileMatchers(any);
        assertThat(anyMatchers.matches(new RemoteObject("folder\\good.csv", 1, NOW))).isTrue();
        assertThat(anyMatchers.matches(new RemoteObject("/incoming/file.tmp", 1, NOW))).isFalse();

        assertThat(RemoteFetchSources.leafName("plain.csv")).isEqualTo("plain.csv");
        for (String remotePath : List.of("/incoming/", "/incoming/.", "/incoming/..")) {
            assertThatThrownBy(() -> RemoteFetchSources.leafName(remotePath))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("safe file name");
        }
    }

    private static PublishRecord publishRecord(String hash, int attempts) {
        return new PublishRecord(
                "slice-id", "target", "profile", "slice-name", hash,
                "endpoint", "/out/slice-name", PublishStatus.FAILED, attempts,
                "failed", null, NOW, NOW);
    }
}
