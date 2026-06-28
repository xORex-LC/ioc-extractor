package com.iocextractor.adapter.out.transport.smb;

import com.iocextractor.application.sync.RemoteErrorKind;
import org.junit.jupiter.api.Test;

import java.io.EOFException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;

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

    @Test
    void mapsUnreachableFailures() {
        assertThat(SmbExceptionMapper.classify(new ConnectException("connection refused")))
                .isEqualTo(RemoteErrorKind.UNREACHABLE);
    }
}
