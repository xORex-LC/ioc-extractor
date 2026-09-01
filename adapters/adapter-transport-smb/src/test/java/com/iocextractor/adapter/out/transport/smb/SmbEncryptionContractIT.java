package com.iocextractor.adapter.out.transport.smb;

import com.iocextractor.application.tck.junit.ContractTest;
import com.iocextractor.application.tck.junit.ExternalTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** Mock-free proof that the configured live endpoint satisfies required SMB3 encryption. */
@EnabledIfSystemProperty(named = "ioc.smb.encryption.contract", matches = "true")
@ExternalTest
@ContractTest
class SmbEncryptionContractIT {

    @Test
    void requiredEncryptionNegotiatesBeforeRemoteOperations() {
        SmbEndpointSettings settings = SmbContractTestSupport.settings();

        assertThat(settings.encryption()).isEqualTo(SmbEncryptionPolicy.REQUIRED);
        assertThatCode(() -> {
            try (SmbShareClient client = new SmbjShareClientFactory().open(settings)) {
                client.list(SmbContractTestSupport.remoteRoot());
            }
        }).doesNotThrowAnyException();
    }
}
