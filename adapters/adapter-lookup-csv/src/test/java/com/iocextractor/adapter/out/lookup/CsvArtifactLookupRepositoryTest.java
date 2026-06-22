package com.iocextractor.adapter.out.lookup;

import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.domain.model.SourceContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CsvArtifactLookupRepositoryTest {

    @TempDir
    Path tempDir;

    @Test
    void checks_masks_and_hashes_against_their_artifacts() throws Exception {
        Path masks = tempDir.resolve("masks.csv");
        Files.writeString(masks, "\"id\";\"mask\"\r\n\"10\";\"example.com\"\r\n");
        Path ips = tempDir.resolve("ip_list.csv");
        Files.writeString(ips, "\"id\";\"ip\"\r\n\"692\";\"1.2.3.4\"\r\n");
        Path hashes = tempDir.resolve("hashes.csv");
        Files.writeString(hashes, "\"id\";\"hash_md5\";\"hash_sha256\";\"hash_sha1\"\r\n"
                + "\"100\";\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\";NULL;NULL\r\n");

        var lookup = new CsvArtifactLookupRepository(Map.of("masks", masks, "ip_list", ips, "hashes", hashes));

        assertThat(lookup.contains(new Indicator("example.com", IndicatorType.DOMAIN, new SourceContext(null, null)))).isTrue();
        assertThat(lookup.contains(new Indicator("1.2.3.4", IndicatorType.IPV4, new SourceContext(null, null)))).isTrue();
        assertThat(lookup.contains(new Indicator("1.2.3.4:8080/payload.exe", IndicatorType.IPV4, new SourceContext(null, null)))).isFalse();
        assertThat(lookup.contains(new Indicator("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", IndicatorType.MD5, new SourceContext(null, null)))).isTrue();
        assertThat(lookup.contains(new Indicator("example.org", IndicatorType.DOMAIN, new SourceContext(null, null)))).isFalse();
        assertThat(lookup.maxId("masks")).isEqualTo(10L);
        assertThat(lookup.maxId("ip_list")).isEqualTo(692L);
        assertThat(lookup.maxId("hashes")).isEqualTo(100L);
        assertThat(lookup.maxId()).isEqualTo(692L);
    }
}
