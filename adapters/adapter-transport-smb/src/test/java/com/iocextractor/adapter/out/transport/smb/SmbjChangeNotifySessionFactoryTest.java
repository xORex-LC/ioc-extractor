package com.iocextractor.adapter.out.transport.smb;

import com.hierynomus.mserref.NtStatus;
import com.hierynomus.mssmb2.messages.SMB2ChangeNotifyResponse;
import com.iocextractor.application.sync.RemoteErrorKind;
import com.iocextractor.application.sync.RemoteTransportException;
import org.junit.jupiter.api.Test;

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

    private SMB2ChangeNotifyResponse response(NtStatus status) {
        SMB2ChangeNotifyResponse response = new SMB2ChangeNotifyResponse();
        response.getHeader().setStatusCode(status.getValue());
        return response;
    }
}
