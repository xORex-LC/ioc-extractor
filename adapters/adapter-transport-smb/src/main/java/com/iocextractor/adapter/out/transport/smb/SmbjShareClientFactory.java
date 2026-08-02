package com.iocextractor.adapter.out.transport.smb;

import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.Share;
import com.iocextractor.application.sync.RemoteErrorKind;
import com.iocextractor.application.sync.RemoteTransportException;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

final class SmbjShareClientFactory implements SmbShareClientFactory {

    @Override
    public SmbShareClient open(SmbEndpointSettings settings) {
        SmbConfig config = config(settings);
        SMBClient client = new SMBClient(config);
        try {
            Connection connection = client.connect(settings.host());
            char[] password = settings.password();
            AuthenticationContext authentication;
            try {
                authentication = new AuthenticationContext(settings.username(), password, settings.domain());
            } finally {
                Arrays.fill(password, '\0');
            }
            Share connectedShare = connection.authenticate(authentication).connectShare(settings.share());
            DiskShare share = requireDiskShare(connectedShare, settings);
            return new SmbjShareClient(client, share);
        } catch (IOException | RuntimeException failure) {
            client.close();
            throw SmbExceptionMapper.map(failure, "connect", settings.name());
        }
    }

    static SmbConfig config(SmbEndpointSettings settings) {
        return SmbConfig.builder()
                .withEncryptData(settings.encrypt())
                .withSocketFactory(new ConnectTimeoutSocketFactory(settings.connectTimeout()))
                .withSoTimeout(0)
                .withTimeout(settings.requestTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .build();
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
