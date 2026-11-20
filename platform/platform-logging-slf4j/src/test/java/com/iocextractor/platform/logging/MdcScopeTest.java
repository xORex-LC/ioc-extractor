package com.iocextractor.platform.logging;

import ch.qos.logback.classic.util.LogbackMDCAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.iocextractor.platform.logging.LogCapture.*;
import static org.assertj.core.api.Assertions.*;

@ResourceLock("slf4j-mdc-adapter")
class MdcScopeTest {
    @AfterEach
    void clearContext() {
        MDC.clear();
    }

    @Test
    void nestedScopesRestoreTheFirstPreviousValueAndPreserveUnrelatedWrites() {
        MDC.put(TASK.key(), "ambient");
        try (var outer = MdcScope.open()) {
            outer.put(TASK, "outer").put(TASK, "updated");
            try (var inner = MdcScope.open()) {
                inner.put(TASK, "inner").hide(TASK);
                assertThat(MDC.get(TASK.key())).isNull();
            }
            assertThat(MDC.get(TASK.key())).isEqualTo("updated");
            MDC.put("collector.unowned", "keep");
        }
        assertThat(MDC.getCopyOfContextMap()).containsExactlyInAnyOrderEntriesOf(
                Map.of(TASK.key(), "ambient", "collector.unowned", "keep"));
    }

    @Test
    void nullAndHideTemporarilyRemoveAmbientKeysOfAnyDeclaredType() {
        MDC.put(TASK.key(), "ambient");
        MDC.put(ITEMS.key(), "ambient-number");
        MDC.put(RETRY.key(), "ambient-boolean");
        try (var scope = MdcScope.open()) {
            scope.put(TASK, null).hide(ITEMS).hide(RETRY);
            assertThat(MDC.getCopyOfContextMap()).isEmpty();
        }
        assertThat(MDC.getCopyOfContextMap()).containsExactlyInAnyOrderEntriesOf(
                Map.of(TASK.key(), "ambient", ITEMS.key(), "ambient-number", RETRY.key(), "ambient-boolean"));
    }

    @Test
    void invalidPopulationStillClosesAnAlreadyAcquiredScope() {
        assertThatIllegalArgumentException().isThrownBy(() -> {
            try (var scope = MdcScope.open()) {
                scope.put(TASK, "temporary").put(ITEMS, "12");
            }
        }).withMessageContaining("LONG");
        assertThat(MDC.get(TASK.key())).isNull();
        assertThat(MDC.get(ITEMS.key())).isNull();
    }

    @Test
    void bodyFailureRestoresContextAndPropagatesTheSameFailure() {
        var failure = new IllegalStateException("operation failed");
        MDC.put(TASK.key(), "ambient");
        assertThatThrownBy(() -> {
            try (var scope = MdcScope.open()) {
                scope.put(TASK, "operation");
                throw failure;
            }
        }).isSameAs(failure);
        assertThat(MDC.get(TASK.key())).isEqualTo("ambient");
    }

    @Test
    void successfulCloseIsIdempotentAndRejectsFurtherWrites() {
        var scope = MdcScope.open();
        scope.put(TASK, "temporary");
        scope.close();
        MDC.put(TASK.key(), "later");
        scope.close();
        assertThat(MDC.get(TASK.key())).isEqualTo("later");
        assertThatIllegalStateException().isThrownBy(() -> scope.put(TASK, "bad"));
        assertThatIllegalStateException().isThrownBy(() -> scope.hide(TASK));
    }

    @Test
    void nullDescriptorsAreRejectedBeforeAnyWrite() {
        try (var scope = MdcScope.open()) {
            assertThatNullPointerException().isThrownBy(() -> scope.put(null, "value"));
            assertThatNullPointerException().isThrownBy(() -> scope.hide(null));
        }
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void restoresInReverseFirstTouchOrder() throws Exception {
        var restored = new ArrayList<String>();
        var adapter = new LogbackMDCAdapter() {
            @Override public void put(String key, String value) {
                restored.add(key);
                super.put(key, value);
            }
        };
        try (var provider = new MdcAdapterSwap(adapter)) {
            MDC.put(TASK.key(), "original");
            MDC.put(TOKEN.key(), "original");
            try (var scope = MdcScope.open()) {
                scope.put(TASK, "first").put(TOKEN, "second").put(TASK, "third");
                restored.clear();
            }
            assertThat(restored).containsExactly(TOKEN.key(), TASK.key());
        }
    }

    @Test
    void cleanupFailureIsSuppressedOnTheOriginalBodyFailure() throws Exception {
        var bodyFailure = new IllegalStateException("operation failed");
        var cleanupFailure = new IllegalStateException("MDC restoration failed");
        var adapter = new LogbackMDCAdapter() {
            @Override public void put(String key, String value) {
                if ("original".equals(value)) {
                    throw cleanupFailure;
                }
                super.put(key, value);
            }
        };
        adapter.setContextMap(Map.of(TASK.key(), "original"));
        try (var provider = new MdcAdapterSwap(adapter)) {
            assertThatThrownBy(() -> {
                try (var scope = MdcScope.open()) {
                    scope.put(TASK, "temporary");
                    throw bodyFailure;
                }
            }).isSameAs(bodyFailure).satisfies(failure ->
                    assertThat(failure.getSuppressed()).containsExactly(cleanupFailure));
        }
    }

    @Test
    @Timeout(5)
    void workerReconstructsAndRemovesItsOwnContext() throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        MDC.put(TASK.key(), "parent");
        try {
            var result = worker.submit(() -> {
                assertThat(MDC.get(TASK.key())).isNull();
                try (var scope = MdcScope.open()) {
                    scope.put(TASK, "from-command");
                    assertThat(MDC.get(TASK.key())).isEqualTo("from-command");
                }
                return MDC.get(TASK.key());
            });
            assertThat(result.get(2, TimeUnit.SECONDS)).isNull();
            assertThat(MDC.get(TASK.key())).isEqualTo("parent");
        } finally {
            worker.shutdownNow();
            assertThat(worker.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }
}
