package com.iocextractor.adapter.out.transport.smb;

import com.hierynomus.mssmb2.SMB2MessageCommandCode;
import com.hierynomus.mssmb2.SMBApiException;
import com.iocextractor.application.sync.RemoteErrorKind;
import com.iocextractor.application.sync.RemoteTransportException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.EOFException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class SmbExceptionMapperTest {

    @Test
    void mapsAuthenticationFailures() {
        assertThat(SmbExceptionMapper.classify(new RuntimeException("STATUS_LOGON_FAILURE")))
                .isEqualTo(RemoteErrorKind.AUTH_FAILED);
    }

    @Test
    void mapsPermissionFailures() {
        assertThat(SmbExceptionMapper.classify(new RuntimeException("STATUS_ACCESS_DENIED")))
                .isEqualTo(RemoteErrorKind.PERMISSION_DENIED);
    }

    @Test
    void mapsNotFoundFailures() {
        assertThat(SmbExceptionMapper.classify(new RuntimeException("STATUS_OBJECT_NAME_NOT_FOUND")))
                .isEqualTo(RemoteErrorKind.NOT_FOUND);
    }

    @Test
    void mapsTransientFailures() {
        assertThat(SmbExceptionMapper.classify(new SocketTimeoutException("read timeout")))
                .isEqualTo(RemoteErrorKind.TRANSIENT);
        assertThat(SmbExceptionMapper.classify(new EOFException("broken stream")))
                .isEqualTo(RemoteErrorKind.TRANSIENT);
    }

    @ParameterizedTest
    @MethodSource("unreachableFailures")
    void mapsUnreachableFailures(Exception failure) {
        assertThat(SmbExceptionMapper.classify(failure))
                .isEqualTo(RemoteErrorKind.UNREACHABLE);
    }

    @Test
    void preservesAlreadyMappedTransportFailure() {
        RemoteTransportException failure = new RemoteTransportException(
                RemoteErrorKind.PERMISSION_DENIED,
                "already classified");

        assertThat(SmbExceptionMapper.map(failure, "list", "delivery"))
                .isSameAs(failure);
    }

    @Test
    void suppliesFailureTypeWhenMappedFailureHasNoMessage() {
        IllegalStateException failure = new IllegalStateException();

        RemoteTransportException mapped = SmbExceptionMapper.map(failure, "list", "delivery");

        assertThat(mapped.kind()).isEqualTo(RemoteErrorKind.TRANSIENT);
        assertThat(mapped)
                .hasMessage("SMB list failed for endpoint 'delivery': IllegalStateException")
                .hasCause(failure);
    }

    @ParameterizedTest
    @ValueSource(longs = {
            0xC000009AL,
            0xC00000CEL,
            0xC00000D0L,
            0xC000013DL,
            0xC0000205L,
            0xC0000259L
    })
    void mapsResourceExhaustionByRawNtStatus(long statusCode) {
        var failure = new SMBApiException(
                statusCode,
                SMB2MessageCommandCode.SMB2_SESSION_SETUP,
                "server rejected session",
                null);

        assertThat(SmbExceptionMapper.classify(failure))
                .isEqualTo(RemoteErrorKind.RESOURCE_EXHAUSTED);
    }

    private static Stream<Exception> unreachableFailures() {
        return Stream.of(
                new UnknownHostException("host missing"),
                new ConnectException("connection refused"),
                new NoRouteToHostException("network unreachable"));
    }
}
