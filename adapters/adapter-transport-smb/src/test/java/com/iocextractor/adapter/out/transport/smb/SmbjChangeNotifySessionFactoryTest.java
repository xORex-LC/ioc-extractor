package com.iocextractor.adapter.out.transport.smb;

import com.hierynomus.mserref.NtStatus;
import com.hierynomus.mssmb2.messages.SMB2ChangeNotifyResponse;
import com.iocextractor.application.sync.RemoteErrorKind;
import com.iocextractor.application.sync.RemoteTransportException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SmbjChangeNotifySessionFactoryTest {

    @Test
    void notifyEnumDirStatusIsOverflowSignal() {
        SmbChangeNotifyResult result = SmbjChangeNotifySessionFactory.resultFrom(
                "primary",
                response(NtStatus.STATUS_NOTIFY_ENUM_DIR));

        assertThat(result)
                .extracting(SmbChangeNotifyResult::changeCount, SmbChangeNotifyResult::overflow,
                        SmbChangeNotifyResult::shouldSignal)
                .containsExactly(0, true, true);
    }

    @Test
    void emptySuccessResponseIsNotOverflow() {
        SmbChangeNotifyResult result = SmbjChangeNotifySessionFactory.resultFrom(
                "primary",
                response(NtStatus.STATUS_SUCCESS));

        assertThat(result)
                .extracting(SmbChangeNotifyResult::changeCount, SmbChangeNotifyResult::overflow,
                        SmbChangeNotifyResult::shouldSignal)
                .containsExactly(0, false, false);
    }

    @Test
    void errorStatusIsMappedToWatchFailure() {
        assertThatThrownBy(() -> SmbjChangeNotifySessionFactory.resultFrom(
                "primary",
                response(NtStatus.STATUS_ACCESS_DENIED)))
                .isInstanceOfSatisfying(RemoteTransportException.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(RemoteErrorKind.PERMISSION_DENIED);
                    assertThat(failure.getMessage()).contains("SMB watch failed");
                });
    }

    @Test
    void pendingAwaitReturnsEmptyWhenFutureIsStillPending() {
        PendingFuture future = new PendingFuture();
        SmbChangeNotifyPending pending = pending(future);

        Optional<SmbChangeNotifyResult> result = pending.await(Duration.ofMillis(10));

        assertThat(result).isEmpty();
        assertThat(future.timedGetCalls).hasValue(0);
        assertThat(future.getCalls).hasValue(0);
    }

    @Test
    void pendingAwaitReadsCompletedFutureWithoutTimedGet() {
        CompletedFuture future = new CompletedFuture(response(NtStatus.STATUS_SUCCESS));
        SmbChangeNotifyPending pending = pending(future);

        Optional<SmbChangeNotifyResult> result = pending.await(Duration.ofMillis(10));

        assertThat(result).hasValueSatisfying(value -> assertThat(value.shouldSignal()).isFalse());
        assertThat(future.timedGetCalls).hasValue(0);
    }

    @Test
    void pendingAwaitMapsCompletedTimeoutAsWatchFailure() {
        SmbChangeNotifyPending pending = pending(new FailedFuture(new TimeoutException("Timeout expired")));

        assertThatThrownBy(() -> pending.await(Duration.ofMillis(10)))
                .isInstanceOfSatisfying(RemoteTransportException.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(RemoteErrorKind.TRANSIENT);
                    assertThat(failure.getMessage()).contains("SMB watch failed");
                });
    }

    @Test
    void pendingAwaitMapsNonTimeoutExecutionFailure() {
        SmbChangeNotifyPending pending = pending(new FailedFuture(new IOException("connection reset")));

        assertThatThrownBy(() -> pending.await(Duration.ofMillis(10)))
                .isInstanceOfSatisfying(RemoteTransportException.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(RemoteErrorKind.TRANSIENT);
                    assertThat(failure.getCause()).isInstanceOf(IOException.class);
                });
    }

    private static SmbChangeNotifyPending pending(Future<SMB2ChangeNotifyResponse> future) {
        return new SmbjChangeNotifySessionFactory.SmbjChangeNotifyPending("primary", future);
    }

    private SMB2ChangeNotifyResponse response(NtStatus status) {
        SMB2ChangeNotifyResponse response = new SMB2ChangeNotifyResponse();
        response.getHeader().setStatusCode(status.getValue());
        return response;
    }

    private abstract static class TestFuture implements Future<SMB2ChangeNotifyResponse> {

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public SMB2ChangeNotifyResponse get() throws InterruptedException, ExecutionException {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public SMB2ChangeNotifyResponse get(long timeout, TimeUnit unit) {
            throw new AssertionError("timed get must not be used for change notify polling");
        }
    }

    private static final class PendingFuture extends TestFuture {
        private final AtomicInteger getCalls = new AtomicInteger();
        private final AtomicInteger timedGetCalls = new AtomicInteger();

        @Override
        public boolean isDone() {
            return false;
        }

        @Override
        public SMB2ChangeNotifyResponse get() throws InterruptedException, ExecutionException {
            getCalls.incrementAndGet();
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public SMB2ChangeNotifyResponse get(long timeout, TimeUnit unit) {
            timedGetCalls.incrementAndGet();
            throw new AssertionError("timed get must not be used for change notify polling");
        }
    }

    private static final class FailedFuture extends TestFuture {
        private final Throwable failure;

        private FailedFuture(Throwable failure) {
            this.failure = failure;
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public SMB2ChangeNotifyResponse get() throws InterruptedException, ExecutionException {
            throw new ExecutionException(failure);
        }
    }

    private static final class CompletedFuture extends TestFuture {
        private final SMB2ChangeNotifyResponse response;
        private final AtomicInteger timedGetCalls = new AtomicInteger();

        private CompletedFuture(SMB2ChangeNotifyResponse response) {
            this.response = response;
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public SMB2ChangeNotifyResponse get() {
            return response;
        }

        @Override
        public SMB2ChangeNotifyResponse get(long timeout, TimeUnit unit) {
            timedGetCalls.incrementAndGet();
            throw new AssertionError("timed get must not be used for change notify polling");
        }
    }
}
