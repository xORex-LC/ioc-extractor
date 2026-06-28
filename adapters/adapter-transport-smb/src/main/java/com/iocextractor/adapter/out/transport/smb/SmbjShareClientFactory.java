package com.iocextractor.adapter.out.transport.smb;

import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.share.DiskShare;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

final class SmbjShareClientFactory implements SmbShareClientFactory {

    @Override
    public SmbShareClient open(SmbEndpointSettings settings) {
        SmbConfig config = SmbConfig.builder()
                .withEncryptData(settings.encrypt())
                .withSoTimeout(settings.connectTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .withTimeout(settings.readTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .build();
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
            DiskShare share = (DiskShare) connection.authenticate(authentication).connectShare(settings.share());
            return new SmbjShareClient(client, share);
        } catch (IOException | RuntimeException failure) {
            client.close();
            throw SmbExceptionMapper.map(failure, "connect", settings.name());
        }
    }
}
