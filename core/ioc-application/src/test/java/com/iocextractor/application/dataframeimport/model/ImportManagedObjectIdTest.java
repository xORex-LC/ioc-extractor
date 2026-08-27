package com.iocextractor.application.dataframeimport.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImportManagedObjectIdTest {

    @Test
    void preservesLegacyDeliveryTokenFormula() {
        assertThat(ImportManagedObjectId.from(new ImportDeliveryId("delivery-1")).value())
                .isEqualTo("0b220df1969115139ffebb337981298d243a44f84dad5d20d7e7da5fdb34de43");
    }

    @Test
    void rejectsAnythingOutsideClosedLowercaseSha256Grammar() {
        assertThatThrownBy(() -> new ImportManagedObjectId("../terminal"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ImportManagedObjectId("A".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
