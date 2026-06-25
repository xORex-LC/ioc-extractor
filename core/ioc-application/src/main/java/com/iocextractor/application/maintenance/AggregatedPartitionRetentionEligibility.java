package com.iocextractor.application.maintenance;

import com.iocextractor.application.ingest.IngestionRecord;
import com.iocextractor.application.port.out.ingest.IngestionLedger;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Allows partition retention only for partition files that belong to already
 * aggregated ledger records. Non-partition targets keep the normal allow-all
 * behavior.
 */
public final class AggregatedPartitionRetentionEligibility implements RetentionEligibility {

    private static final String PARTITIONS_TARGET = "partitions";

    private final IngestionLedger ledger;

    public AggregatedPartitionRetentionEligibility(IngestionLedger ledger) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
    }

    @Override
    public List<RetentionEntry> eligibleEntries(RetentionTarget target, List<RetentionEntry> entries) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(entries, "entries");
        if (!PARTITIONS_TARGET.equalsIgnoreCase(target.name())) {
            return List.copyOf(entries);
        }
        Set<Path> aggregatedPartitions = ledger.findAggregated().stream()
                .map(IngestionRecord::partitions)
                .flatMap(List::stream)
                .map(AggregatedPartitionRetentionEligibility::normalized)
                .collect(Collectors.toUnmodifiableSet());
        return entries.stream()
                .filter(entry -> aggregatedPartitions.contains(normalized(entry.path())))
                .toList();
    }

    private static Path normalized(Path path) {
        return path.toAbsolutePath().normalize();
    }
}
