package com.iocextractor.adapter.out.transport.smb;

import com.hierynomus.mserref.NtStatus;
import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.mssmb2.SMB2CompletionFilter;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2CreateOptions;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.mssmb2.SMBApiException;
import com.hierynomus.mssmb2.messages.SMB2ChangeNotifyResponse;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.share.Directory;
import com.hierynomus.smbj.share.DiskShare;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

final class SmbjChangeNotifySessionFactory implements SmbChangeNotifySessionFactory {

    private static final Set<AccessMask> DIRECTORY_ACCESS = EnumSet.of(
            AccessMask.FILE_LIST_DIRECTORY,
            AccessMask.FILE_READ_ATTRIBUTES,
            AccessMask.SYNCHRONIZE);
    private static final Set<FileAttributes> DIRECTORY_ATTRIBUTES = EnumSet.of(FileAttributes.FILE_ATTRIBUTE_DIRECTORY);
    private static final Set<SMB2ShareAccess> SHARE_ALL = EnumSet.of(
            SMB2ShareAccess.FILE_SHARE_READ,
            SMB2ShareAccess.FILE_SHARE_WRITE,
            SMB2ShareAccess.FILE_SHARE_DELETE);
    private static final Set<SMB2CreateOptions> DIRECTORY_OPTIONS = EnumSet.of(SMB2CreateOptions.FILE_DIRECTORY_FILE);
    private static final Set<SMB2CompletionFilter> FILTERS = EnumSet.of(
            SMB2CompletionFilter.FILE_NOTIFY_CHANGE_FILE_NAME,
            SMB2CompletionFilter.FILE_NOTIFY_CHANGE_SIZE,
            SMB2CompletionFilter.FILE_NOTIFY_CHANGE_LAST_WRITE);

    @Override
    public SmbChangeNotifySession open(SmbEndpointSettings settings, String remotePath) {
        String normalizedPath = SmbFileTransport.normalizeRemotePath(remotePath);
        SMBClient client = new SMBClient(SmbjShareClientFactory.config(settings));
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
            Directory directory = share.openDirectory(
                    toSmbPath(normalizedPath),
                    DIRECTORY_ACCESS,
                    DIRECTORY_ATTRIBUTES,
                    SHARE_ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    DIRECTORY_OPTIONS);
            return new SmbjChangeNotifySession(settings.name(), client, share, directory);
        } catch (RuntimeException | IOException failure) {
            client.close();
            throw SmbExceptionMapper.map(failure, "watch", settings.name());
        }
    }

    private static String toSmbPath(String remotePath) {
        return remotePath.replace('/', '\\');
    }

    static SmbChangeNotifyResult resultFrom(String endpoint, SMB2ChangeNotifyResponse response) {
        long statusCode = response.getHeader().getStatusCode();
        if (statusCode == NtStatus.STATUS_NOTIFY_ENUM_DIR.getValue()) {
            return new SmbChangeNotifyResult(0, true);
        }
        if (statusCode != NtStatus.STATUS_SUCCESS.getValue()) {
            throw SmbExceptionMapper.map(
                    new SMBApiException(response.getHeader(), "Change notify failed"),
                    "watch",
                    endpoint);
        }
        List<?> changes = response.getFileNotifyInfoList();
        int changeCount = changes == null ? 0 : changes.size();
        return new SmbChangeNotifyResult(changeCount, false);
    }

    private record SmbjChangeNotifySession(String endpoint,
                                           SMBClient client,
                                           DiskShare share,
                                           Directory directory) implements SmbChangeNotifySession {

        @Override
        public SmbChangeNotifyPending watch() {
            return new SmbjChangeNotifyPending(endpoint, directory.watchAsync(FILTERS, false));
        }

        @Override
        public void close() {
            try {
                directory.close();
            } catch (RuntimeException ignored) {
                // SMBJ client close below is the final cleanup authority.
            }
            try {
                share.close();
            } catch (IOException | RuntimeException ignored) {
                // Best-effort close; client close tears down remaining connections.
            } finally {
                client.close();
            }
        }
    }

    record SmbjChangeNotifyPending(String endpoint,
                                    Future<SMB2ChangeNotifyResponse> future) implements SmbChangeNotifyPending {

        @Override
        public Optional<SmbChangeNotifyResult> await(Duration timeout) {
            if (!future.isDone()) {
                pause(timeout);
                if (!future.isDone()) {
                    return Optional.empty();
                }
            }
            try {
                SMB2ChangeNotifyResponse response = future.get();
                return Optional.of(resultFrom(endpoint, response));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for SMB change notify", interrupted);
            } catch (CancellationException cancelled) {
                throw SmbExceptionMapper.map(cancelled, "watch", endpoint);
            } catch (ExecutionException failure) {
                Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                throw SmbExceptionMapper.map(cause, "watch", endpoint);
            }
        }

        private static void pause(Duration timeout) {
            try {
                Thread.sleep(timeout.toMillis(), timeout.toNanosPart() % 1_000_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for SMB change notify", interrupted);
            }
        }

        @Override
        public boolean cancel() {
            return future.cancel(true);
        }
    }
}
