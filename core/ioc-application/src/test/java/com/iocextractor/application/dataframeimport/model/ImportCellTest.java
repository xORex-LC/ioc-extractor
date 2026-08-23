package com.iocextractor.application.dataframeimport.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class ImportCellTest {

    @Test
    void preservesAbsentNullAndConcreteValueAsDifferentStates() {
        ImportCell absent = ImportCell.absent();
        ImportCell nullValue = ImportCell.nullValue();
        ImportCell value = ImportCell.value("");

        assertThat(absent.presence()).isEqualTo(ImportCell.Presence.ABSENT);
        assertThat(nullValue.presence()).isEqualTo(ImportCell.Presence.NULL);
        assertThat(value).isEqualTo(new ImportCell(ImportCell.Presence.VALUE, ""));
    }

    @Test
    void rejectsContradictoryRepresentations() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ImportCell(ImportCell.Presence.NULL, "value"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ImportCell(ImportCell.Presence.VALUE, null));
        assertThatNullPointerException()
                .isThrownBy(() -> new ImportCell(null, null));
    }
}
