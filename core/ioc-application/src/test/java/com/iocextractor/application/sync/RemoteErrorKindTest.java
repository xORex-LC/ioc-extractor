package com.iocextractor.application.sync;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteErrorKindTest {

    @Test
    void dispositions_are_code_owned_and_stable() {
        assertThat(RemoteErrorKind.UNREACHABLE.disposition()).isEqualTo(RemoteErrorDisposition.RETRY_LATER);
        assertThat(RemoteErrorKind.AUTH_FAILED.disposition()).isEqualTo(RemoteErrorDisposition.FAIL);
        assertThat(RemoteErrorKind.PERMISSION_DENIED.disposition()).isEqualTo(RemoteErrorDisposition.FAIL);
        assertThat(RemoteErrorKind.NOT_FOUND.disposition()).isEqualTo(RemoteErrorDisposition.FAIL);
        assertThat(RemoteErrorKind.TRANSIENT.disposition()).isEqualTo(RemoteErrorDisposition.RETRY_NOW);
    }
}
