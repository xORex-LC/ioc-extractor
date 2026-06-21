package com.iocextractor.adapter.out.psl;

import com.iocextractor.domain.feature.HostKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class PslHostClassifierTest {

    private final PslHostClassifier classifier = new PslHostClassifier();

    @ParameterizedTest
    @CsvSource({
            "example.com,                                REGISTRABLE",
            "zeccecard.com,                              REGISTRABLE",
            "a.example.com,                              SUBDOMAIN",
            "zzzzjm2.mlcrosoft.asia,                     SUBDOMAIN",
            "159.198.41.140,                             IP",
            "yasminanthonyy.workers.dev,                 REGISTRABLE",  // workers.dev = PSL private suffix
            "dawn-bush-ddd1.yasminanthonyy.workers.dev,  SUBDOMAIN",
            "daroughgan8hajous20.duckdns.org,            REGISTRABLE",  // duckdns.org = PSL private suffix
            "cs371620.tw1.ru,                            SUBDOMAIN"     // tw1.ru not a suffix
    })
    void classifies_hosts(String host, HostKind expected) {
        assertThat(classifier.classify(host)).isEqualTo(expected);
    }

    @Test
    void onion_address() {
        assertThat(classifier.classify("pacrhxuvp7jkk6lrpo3qcdfus2y2jsn25cpbsy3mqncnhbs6gpzi5aad.onion"))
                .isEqualTo(HostKind.ONION);
    }

    @Test
    void blank_host_is_unknown() {
        assertThat(classifier.classify("")).isEqualTo(HostKind.UNKNOWN);
    }
}
