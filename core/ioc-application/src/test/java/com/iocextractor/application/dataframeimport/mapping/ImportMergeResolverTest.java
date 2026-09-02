package com.iocextractor.application.dataframeimport.mapping;

import com.iocextractor.application.dataframeimport.model.ImportCell;
import com.iocextractor.application.dataframeimport.model.ImportMergePolicy;
import org.junit.jupiter.api.Test;

import static com.iocextractor.application.dataframeimport.mapping.ImportMergeResult.Decision.CLEAR;
import static com.iocextractor.application.dataframeimport.mapping.ImportMergeResult.Decision.CONFLICT;
import static com.iocextractor.application.dataframeimport.mapping.ImportMergeResult.Decision.SET;
import static com.iocextractor.application.dataframeimport.mapping.ImportMergeResult.Decision.UNCHANGED;
import static org.assertj.core.api.Assertions.assertThat;

class ImportMergeResolverTest {

    private final ImportMergeResolver resolver = new ImportMergeResolver();

    @Test
    void absentCellNeverMutatesAnExistingRecord() {
        for (ImportMergePolicy policy : ImportMergePolicy.values()) {
            assertThat(resolver.resolve(true, "old", ImportCell.absent(), policy).decision())
                    .as(policy.name())
                    .isEqualTo(UNCHANGED);
        }
    }

    @Test
    void authoritativePolicyTreatsNullAsAnExplicitClear() {
        ImportMergeResult result = resolver.resolve(
                true, "old", ImportCell.nullValue(), ImportMergePolicy.AUTHORITATIVE);

        assertThat(result.decision()).isEqualTo(CLEAR);
        assertThat(result.value()).isNull();
    }

    @Test
    void replaceNonNullNeverClearsAnExistingValue() {
        assertThat(resolver.resolve(true, "old", ImportCell.nullValue(), ImportMergePolicy.REPLACE_NON_NULL)
                .decision()).isEqualTo(UNCHANGED);
        assertThat(resolver.resolve(true, "old", ImportCell.value("new"), ImportMergePolicy.REPLACE_NON_NULL))
                .isEqualTo(new ImportMergeResult(SET, "new"));
    }

    @Test
    void rejectConflictAcceptsMissingDataButRejectsContradictions() {
        assertThat(resolver.resolve(true, null, ImportCell.value("new"), ImportMergePolicy.REJECT_CONFLICT)
                .decision()).isEqualTo(SET);
        assertThat(resolver.resolve(true, "old", ImportCell.value("new"), ImportMergePolicy.REJECT_CONFLICT)
                .decision()).isEqualTo(CONFLICT);
        assertThat(resolver.resolve(true, "old", ImportCell.nullValue(), ImportMergePolicy.REJECT_CONFLICT)
                .decision()).isEqualTo(CONFLICT);
    }

    @Test
    void newRecordUsesConcreteValuesAndIgnoresNullForEveryPolicy() {
        for (ImportMergePolicy policy : ImportMergePolicy.values()) {
            assertThat(resolver.resolve(false, null, ImportCell.value("new"), policy).decision())
                    .as(policy.name()).isEqualTo(SET);
            assertThat(resolver.resolve(false, null, ImportCell.nullValue(), policy).decision())
                    .as(policy.name()).isEqualTo(UNCHANGED);
        }
    }

    @Test
    void evaluatesEveryExistingRecordPolicyAgainstEqualMissingAndChangedValues() {
        assertThat(resolver.resolve(true, "old", ImportCell.value("new"), ImportMergePolicy.KEEP_EXISTING))
                .isEqualTo(new ImportMergeResult(UNCHANGED, null));

        assertThat(resolver.resolve(true, null, ImportCell.value("new"), ImportMergePolicy.FILL_MISSING))
                .isEqualTo(new ImportMergeResult(SET, "new"));
        assertThat(resolver.resolve(true, "old", ImportCell.value("new"), ImportMergePolicy.FILL_MISSING))
                .isEqualTo(new ImportMergeResult(UNCHANGED, null));
        assertThat(resolver.resolve(true, null, ImportCell.nullValue(), ImportMergePolicy.FILL_MISSING))
                .isEqualTo(new ImportMergeResult(UNCHANGED, null));

        assertThat(resolver.resolve(true, "old", ImportCell.value("old"), ImportMergePolicy.REPLACE_NON_NULL))
                .isEqualTo(new ImportMergeResult(UNCHANGED, null));

        assertThat(resolver.resolve(true, null, ImportCell.nullValue(), ImportMergePolicy.AUTHORITATIVE))
                .isEqualTo(new ImportMergeResult(UNCHANGED, null));
        assertThat(resolver.resolve(true, "old", ImportCell.value("old"), ImportMergePolicy.AUTHORITATIVE))
                .isEqualTo(new ImportMergeResult(UNCHANGED, null));
        assertThat(resolver.resolve(true, "old", ImportCell.value("new"), ImportMergePolicy.AUTHORITATIVE))
                .isEqualTo(new ImportMergeResult(SET, "new"));

        assertThat(resolver.resolve(true, null, ImportCell.nullValue(), ImportMergePolicy.REJECT_CONFLICT))
                .isEqualTo(new ImportMergeResult(UNCHANGED, null));
        assertThat(resolver.resolve(true, "old", ImportCell.value("old"), ImportMergePolicy.REJECT_CONFLICT))
                .isEqualTo(new ImportMergeResult(UNCHANGED, null));
    }
}
