package com.iocextractor.adapter.out.transport.smb;

import com.hierynomus.mssmb2.SMBApiException;
import com.iocextractor.application.sync.RemoteErrorKind;
import com.iocextractor.application.sync.RemoteTransportException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;

final class SmbExceptionMapper {

    private SmbExceptionMapper() {
    }

    static RemoteTransportException map(Throwable failure, String operation, String endpoint) {
        if (failure instanceof RemoteTransportException remote) {
            return remote;
        }
        RemoteErrorKind kind = classify(failure);
        return new RemoteTransportException(
                kind,
                "SMB " + operation + " failed for endpoint '" + endpoint + "': " + safeMessage(failure),
                failure);
    }

    static RemoteErrorKind classify(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SMBApiException smb) {
                RemoteErrorKind kind = classifyToken(statusToken(smb));
                if (kind != null) {
                    return kind;
                }
            }
            if (current instanceof UnknownHostException
                    || current instanceof ConnectException
                    || current instanceof NoRouteToHostException) {
                return RemoteErrorKind.UNREACHABLE;
            }
            if (current instanceof SocketTimeoutException) {
                return RemoteErrorKind.TRANSIENT;
            }
            if (current instanceof IOException) {
                return RemoteErrorKind.TRANSIENT;
            }
            RemoteErrorKind byText = classifyToken(current.getClass().getName() + " " + current.getMessage());
            if (byText != null) {
                return byText;
            }
            current = current.getCause();
        }
        return RemoteErrorKind.TRANSIENT;
    }

    private static String statusToken(SMBApiException failure) {
        String status = failure.getStatus() == null ? "" : failure.getStatus().name();
        return status + " " + failure.getMessage();
    }

    private static RemoteErrorKind classifyToken(String value) {
        String token = value == null ? "" : value.toUpperCase(Locale.ROOT);
        if (token.contains("LOGON") || token.contains("AUTH")) {
            return RemoteErrorKind.AUTH_FAILED;
        }
        if (token.contains("ACCESS_DENIED") || token.contains("PRIVILEGE") || token.contains("PERMISSION")) {
            return RemoteErrorKind.PERMISSION_DENIED;
        }
        if (token.contains("OBJECT_NAME_NOT_FOUND")
                || token.contains("OBJECT_PATH_NOT_FOUND")
                || token.contains("NO_SUCH")
                || token.contains("NOT_FOUND")
                || token.contains("BAD_NETWORK_NAME")) {
            return RemoteErrorKind.NOT_FOUND;
        }
        if (token.contains("TIMEOUT")
                || token.contains("CONNECTION")
                || token.contains("TRANSPORT")
                || token.contains("NETWORK")
                || token.contains("BROKEN")
                || token.contains("EOF")) {
            return RemoteErrorKind.TRANSIENT;
        }
        return null;
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
