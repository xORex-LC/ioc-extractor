package com.iocextractor.application.tck.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportPromotionOutcome;
import com.iocextractor.application.port.out.dataframeimport.CanonicalImportCommand;
import com.iocextractor.application.port.out.dataframeimport.CanonicalImportResult;
import com.iocextractor.application.port.out.dataframeimport.CanonicalImportWriter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Reusable receipt-idempotency baseline for atomic canonical import writers. */
public abstract class CanonicalImportWriterContractTest {

    /** @return a clean adapter fixture containing a valid non-empty promotion */
    protected abstract Fixture createFixture();

    @Test
    void exactReplayReturnsTheStoredReceiptWithoutReapplyingMutations() {
        Fixture fixture = createFixture();

        CanonicalImportResult committed = fixture.writer().promote(fixture.command());
        CanonicalImportResult replayed = fixture.writer().promote(fixture.command());

        assertThat(committed.outcome()).isEqualTo(ImportPromotionOutcome.COMMITTED);
        assertThat(replayed)
                .usingRecursiveComparison()
                .ignoringFields("outcome")
                .isEqualTo(committed);
        assertThat(replayed.outcome()).isEqualTo(ImportPromotionOutcome.ALREADY_COMMITTED);
        fixture.assertExactlyOneCanonicalEffect().run();
    }

    /**
     * Adapter-provided test fixture. The assertion must verify public rows,
     * lifecycle/alias/slot/revision effects and the receipt as one transaction.
     *
     * @param writer adapter under test
     * @param command valid sealed promotion
     * @param assertExactlyOneCanonicalEffect postcondition assertion
     */
    public record Fixture(
            CanonicalImportWriter writer,
            CanonicalImportCommand command,
            Runnable assertExactlyOneCanonicalEffect) {
    }
}
