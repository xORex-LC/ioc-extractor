package com.iocextractor.adapter.in.cli;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class BuildInfoVersionProviderTest {

    @Test
    void renders_human_facing_identity_and_abbreviates_commit() {
        var build = new ApplicationBuildInfo(
                "9.8.7",
                Optional.of("0123456789abcdef0123456789abcdef01234567"),
                Optional.of(Instant.parse("2026-07-19T10:30:00Z")));
        var provider = new BuildInfoVersionProvider(() -> build);

        assertThat(provider.getVersion()).containsExactly(
                "ioc-extractor 9.8.7",
                "commit: 0123456789ab",
                "built: 2026-07-19T10:30:00Z");
    }

    @Test
    void omits_optional_lines_when_metadata_is_not_embedded() {
        var build = new ApplicationBuildInfo("9.8.7", Optional.empty(), Optional.empty());
        var provider = new BuildInfoVersionProvider(() -> build);

        assertThat(provider.getVersion()).containsExactly("ioc-extractor 9.8.7");
    }
}
