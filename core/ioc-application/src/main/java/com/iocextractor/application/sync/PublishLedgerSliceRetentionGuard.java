package com.iocextractor.application.sync;

import com.iocextractor.application.export.SliceDescriptor;
import com.iocextractor.application.port.out.export.SliceRetentionGuard;
import com.iocextractor.application.port.out.sync.PublishLedger;

import java.util.List;
import java.util.Objects;

/**
 * Delivery-aware slice retention guard backed by configured publish targets and publish ledger.
 */
public final class PublishLedgerSliceRetentionGuard implements SliceRetentionGuard {

    private final PublishLedger ledger;
    private final List<PublishTarget> targets;

    /** Creates a guard that blocks deletion until every configured target reaches a terminal state. */
    public PublishLedgerSliceRetentionGuard(PublishLedger ledger, List<PublishTarget> targets) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
    }

    @Override
    public boolean canDelete(SliceDescriptor slice) {
        Objects.requireNonNull(slice, "slice");
        for (PublishTarget target : targetsFor(slice.profile())) {
            PublishRecord record = ledger.find(slice.sliceId(), target.targetId()).orElse(null);
            if (record == null || !isTerminal(record.status())) {
                return false;
            }
        }
        return true;
    }

    private List<PublishTarget> targetsFor(String profile) {
        return targets.stream()
                .filter(target -> target.exportProfile().equals(profile))
                .toList();
    }

    private boolean isTerminal(PublishStatus status) {
        return status == PublishStatus.SUCCEEDED || status == PublishStatus.ABANDONED;
    }
}
