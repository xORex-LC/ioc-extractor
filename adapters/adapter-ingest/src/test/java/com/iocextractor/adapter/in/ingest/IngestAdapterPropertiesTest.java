package com.iocextractor.adapter.in.ingest;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IngestAdapterPropertiesTest {

    @Test
    void patternsSnapshotCallerListsWithoutRejectingNulls() {
        var include = new ArrayList<>(Arrays.asList("*.docx", null));
        var exclude = new ArrayList<>(Arrays.asList("*.tmp", null));

        var patterns = new IngestAdapterProperties.Patterns(include, exclude);
        include.add("*.html");
        exclude.clear();

        assertThat(patterns.include()).containsExactly("*.docx", null);
        assertThat(patterns.exclude()).containsExactly("*.tmp", null);
        assertThatThrownBy(() -> patterns.include().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> patterns.exclude().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(new IngestAdapterProperties.Patterns(null, null))
                .extracting(IngestAdapterProperties.Patterns::include,
                        IngestAdapterProperties.Patterns::exclude)
                .containsExactly(null, null);
    }
}
