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
        Path hashes = tempDir.resolve("hashes.csv");
        Files.writeString(hashes, "\"id\";\"hash_md5\";\"hash_sha256\";\"hash_sha1\"\r\n"
                + "\"100\";\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\";NULL;NULL\r\n");

        var lookup = new CsvArtifactLookupRepository(Map.of("masks", masks, "hashes", hashes));

        assertThat(lookup.contains(new Indicator("example.com", IndicatorType.DOMAIN, SourceContext.UNKNOWN))).isTrue();
        assertThat(lookup.contains(new Indicator("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", IndicatorType.MD5, SourceContext.UNKNOWN))).isTrue();
        assertThat(lookup.contains(new Indicator("example.org", IndicatorType.DOMAIN, SourceContext.UNKNOWN))).isFalse();
        assertThat(lookup.maxId()).isEqualTo(10L);
    }
}
