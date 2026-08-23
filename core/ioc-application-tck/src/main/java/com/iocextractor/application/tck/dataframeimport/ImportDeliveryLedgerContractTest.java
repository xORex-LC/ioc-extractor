package com.iocextractor.application.tck.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportClaimReservation;
import com.iocextractor.application.dataframeimport.model.ImportDelivery;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryState;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryTransition;
import com.iocextractor.application.dataframeimport.model.ImportLedgerTransitionResult;
import com.iocextractor.application.dataframeimport.model.ImportSourceId;
import com.iocextractor.application.port.out.dataframeimport.ImportDeliveryLedger;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Reusable ordering, idempotency and CAS contract for import delivery ledgers. */
public abstract class ImportDeliveryLedgerContractTest {

    private static final Instant DETECTED_AT = Instant.parse("2026-08-23T00:00:00Z");

    /** @return a clean ledger instance for one test */
    protected abstract ImportDeliveryLedger createLedger();

    @Test
    void reservationsAreIdempotentAndSequencesAreStrictlyMonotonic() {
        ImportDeliveryLedger ledger = createLedger();
        ImportClaimReservation first = reservation("delivery-a", "candidate-a");
        ImportClaimReservation second = reservation("delivery-b", "candidate-b");

        ImportDelivery firstDelivery = ledger.reserveClaim(first);
        ImportDelivery replayed = ledger.reserveClaim(first);
        ImportDelivery secondDelivery = ledger.reserveClaim(second);

        assertThat(replayed).isEqualTo(firstDelivery);
        assertThat(secondDelivery.sequence()).isGreaterThan(firstDelivery.sequence());
        assertThat(ledger.findHead()).contains(firstDelivery);
    }

    @Test
    void transitionUsesStateAndVersionCompareAndSet() {
        ImportDeliveryLedger ledger = createLedger();
        ImportDelivery detected = ledger.reserveClaim(reservation("delivery-cas", "candidate-cas"));
        ImportDeliveryTransition transition = new ImportDeliveryTransition(
                detected.id(), detected.state(), detected.version(), ImportDeliveryState.CLAIMING,
                Optional.empty(), DETECTED_AT.plusSeconds(1));

        assertThat(ledger.transition(transition)).isEqualTo(ImportLedgerTransitionResult.APPLIED);
        assertThat(ledger.transition(transition)).isEqualTo(ImportLedgerTransitionResult.ALREADY_APPLIED);
        assertThat(ledger.find(detected.id())).get().satisfies(updated -> {
            assertThat(updated.state()).isEqualTo(ImportDeliveryState.CLAIMING);
            assertThat(updated.version()).isGreaterThan(detected.version());
        });
    }

    @Test
    void recoveryAndHeadRemainInGlobalSequenceOrder() {
        ImportDeliveryLedger ledger = createLedger();
        ImportDelivery first = ledger.reserveClaim(reservation("delivery-first", "candidate-first"));
        ImportDelivery second = ledger.reserveClaim(reservation("delivery-second", "candidate-second"));

        assertThat(ledger.findRecoverable(2)).containsExactly(first, second);
        assertThat(ledger.findHead()).contains(first);
    }

    private ImportClaimReservation reservation(String deliveryId, String candidateToken) {
        return new ImportClaimReservation(
                new ImportDeliveryId(deliveryId), new ImportSourceId("source"), candidateToken, DETECTED_AT);
    }
}
