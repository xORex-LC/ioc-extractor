package com.iocextractor.application.port.out.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportClaimReservation;
import com.iocextractor.application.dataframeimport.model.ImportDelivery;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryTransition;
import com.iocextractor.application.dataframeimport.model.ImportLedgerTransitionResult;
import com.iocextractor.application.dataframeimport.model.ImportRetrySchedule;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Driven port for durable global claim order and forward-only delivery state.
 * Implementations must make reservation idempotent by delivery ID, allocate a
 * non-reusable monotonic sequence atomically and enforce CAS transitions.
 */
public interface ImportDeliveryLedger {

    /**
     * Creates or returns the exact existing claim reservation without changing its sequence.
     *
     * @param reservation occurrence reservation
     * @return durable delivery in {@code DETECTED} or a later state
     */
    ImportDelivery reserveClaim(ImportClaimReservation reservation);

    /**
     * Finds one delivery.
     *
     * @param deliveryId occurrence identity
     * @return durable aggregate when present
     */
    Optional<ImportDelivery> find(ImportDeliveryId deliveryId);

    /**
     * Returns the minimum nonterminal sequence; later work must never overtake it.
     *
     * @return current global head
     */
    Optional<ImportDelivery> findHead();

    /**
     * Returns the minimum nonterminal sequence only when that head is retry-eligible.
     * A later due delivery must never overtake a deferred head.
     *
     * @param now retry eligibility cutoff
     * @return due global head
     */
    Optional<ImportDelivery> findDueHead(Instant now);

    /**
     * Applies an idempotent forward CAS transition.
     *
     * @param transition requested state change
     * @return applied/already-applied/missing/conflict outcome
     */
    ImportLedgerTransitionResult transition(ImportDeliveryTransition transition);

    /**
     * Applies a same-state CAS retry schedule. Actual failures increment attempts;
     * capacity deferrals only move the eligibility time.
     *
     * @param schedule retry/deferral request
     * @return CAS outcome
     */
    ImportLedgerTransitionResult scheduleRetry(ImportRetrySchedule schedule);

    /**
     * Returns at most {@code limit} nonterminal deliveries in sequence order for recovery.
     *
     * @param limit positive result bound
     * @return ordered recovery records
     */
    List<ImportDelivery> findRecoverable(int limit);
}
