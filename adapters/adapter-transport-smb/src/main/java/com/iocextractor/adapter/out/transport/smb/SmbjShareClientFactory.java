package com.iocextractor.adapter.out.transport.smb;

import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.Share;
import com.iocextractor.application.sync.RemoteErrorKind;
import com.iocextractor.application.sync.RemoteTransportException;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static com.hierynomus.mssmb2.SMB2Dialect.SMB_3_0;
import static com.hierynomus.mssmb2.SMB2Dialect.SMB_3_0_2;
import static com.hierynomus.mssmb2.SMB2Dialect.SMB_3_1_1;

final class SmbjShareClientFactory implements SmbShareClientFactory {

    @Override
    public SmbShareClient open(SmbEndpointSettings settings) {
        SmbConfig config = config(settings);
        SMBClient client = new SMBClient(config);
        try {
            Connection connection = client.connect(settings.host());
            Share connectedShare = authenticate(connection, settings).connectShare(settings.share());
            DiskShare share = requireDiskShare(connectedShare, settings);
            return new SmbjShareClient(client, share);
        } catch (IOException | RuntimeException failure) {
            client.close();
            throw SmbExceptionMapper.map(failure, "connect", settings.name());
        }
    }

    static SmbConfig config(SmbEndpointSettings settings) {
        SmbConfig.Builder builder = SmbConfig.builder()
                .withEncryptData(settings.encryption().requestsEncryption())
                .withSocketFactory(new ConnectTimeoutSocketFactory(settings.connectTimeout()))
                .withSoTimeout(0)
                .withTimeout(settings.requestTimeout().toMillis(), TimeUnit.MILLISECONDS);
        if (settings.encryption().requiresEncryption()) {
            builder.withDialects(SMB_3_1_1, SMB_3_0_2, SMB_3_0);
        }
        return builder.build();
    }

    static Session authenticate(Connection connection, SmbEndpointSettings settings)
            throws IOException {
        char[] password = settings.password();
        AuthenticationContext authentication;
        try {
            authentication = new AuthenticationContext(
                    settings.username(), password, settings.domain());
        } finally {
            Arrays.fill(password, '\0');
        }
        Session session = connection.authenticate(authentication);
        if (settings.encryption().requiresEncryption()) {
            boolean encrypted;
            try {
                encrypted = session.shouldEncryptData();
            } catch (IOException failure) {
                throw securityPolicyFailure(settings, failure);
            }
            requireEncryption(settings, connection.getNegotiatedProtocol().getDialect(), encrypted);
        }
        return session;
    }

    static void requireEncryption(SmbEndpointSettings settings,
                                  com.hierynomus.mssmb2.SMB2Dialect dialect,
                                  boolean encrypted) {
        if (settings.encryption().requiresEncryption() && (!dialect.isSmb3x() || !encrypted)) {
            throw securityPolicyFailure(settings, null);
        }
    }

    private static RemoteTransportException securityPolicyFailure(
            SmbEndpointSettings settings, Throwable cause) {
        String message = "SMB3 encryption is required for endpoint '" + settings.name()
                + "' but was not negotiated";
        return cause == null
                ? new RemoteTransportException(RemoteErrorKind.SECURITY_POLICY_UNMET, message)
                : new RemoteTransportException(RemoteErrorKind.SECURITY_POLICY_UNMET, message, cause);
    }

    static DiskShare requireDiskShare(Share share, SmbEndpointSettings settings) {
        if (share instanceof DiskShare diskShare) {
            return diskShare;
        }
        String actualType = share == null ? "null" : share.getClass().getSimpleName();
        throw new RemoteTransportException(
                RemoteErrorKind.NOT_FOUND,
                "SMB share '" + settings.share() + "' for endpoint '" + settings.name()
                        + "' is not a disk share (actual type: " + actualType + ")");
    }
}
