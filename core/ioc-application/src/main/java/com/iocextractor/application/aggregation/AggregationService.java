package com.iocextractor.application.aggregation;

import com.iocextractor.application.ingest.IngestionRecord;
import com.iocextractor.application.port.in.aggregation.AggregatePartitionsUseCase;
import com.iocextractor.application.port.in.aggregation.AggregationCommand;
import com.iocextractor.application.port.in.aggregation.AggregationResult;
import com.iocextractor.application.port.out.aggregation.ArtifactIdentityResolver;
import com.iocextractor.application.port.out.aggregation.CanonicalArtifactRepository;
import com.iocextractor.application.port.out.aggregation.PartitionArtifactRepository;
import com.iocextractor.application.port.out.aggregation.StableIdIndex;
import com.iocextractor.application.port.out.ingest.IngestionLedger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Application process manager for daemon aggregation. It coordinates ledger
 * checkpoints and aggregation ports, while CSV parsing/writing and row identity
 * extraction stay in adapter implementations.
 */
public final class AggregationService implements AggregatePartitionsUseCase {

    private final IngestionLedger ledger;
    private final PartitionArtifactRepository partitionRepository;
    private final CanonicalArtifactRepository canonicalRepository;
    private final ArtifactIdentityResolver identityResolver;
    private final StableIdIndex stableIdIndex;
    private final ArtifactMergePolicy mergePolicy;
    private final List<String> artifactOrder;

    public AggregationService(IngestionLedger ledger,
                              PartitionArtifactRepository partitionRepository,
                              CanonicalArtifactRepository canonicalRepository,
                              ArtifactIdentityResolver identityResolver,
                              StableIdIndex stableIdIndex,
                              ArtifactMergePolicy mergePolicy,
                              List<String> artifactOrder) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.partitionRepository = Objects.requireNonNull(partitionRepository, "partitionRepository");
        this.canonicalRepository = Objects.requireNonNull(canonicalRepository, "canonicalRepository");
        this.identityResolver = Objects.requireNonNull(identityResolver, "identityResolver");
        this.stableIdIndex = Objects.requireNonNull(stableIdIndex, "stableIdIndex");
        this.mergePolicy = Objects.requireNonNull(mergePolicy, "mergePolicy");
        this.artifactOrder = List.copyOf(Objects.requireNonNull(artifactOrder, "artifactOrder"));
    }

    @Override
    public AggregationResult aggregate(AggregationCommand command) {
        Objects.requireNonNull(command, "command");
        List<IngestionRecord> records = readyRecords();
        if (records.isEmpty()) {
            return AggregationResult.empty();
        }

        List<PartitionArtifact> partitions = partitionRepository.readPartitions(records);
        Map<String, List<PartitionArtifact>> byArtifact = groupByArtifact(partitions);
        Set<String> requestedArtifacts = Set.copyOf(command.artifacts());
        List<String> activeArtifacts = artifactOrder.stream()
                .filter(name -> requestedArtifacts.isEmpty() || requestedArtifacts.contains(name))
                .toList();

        var accumulator = new ResultAccumulator(records.size(), partitions.size());
        Map<String, CanonicalArtifact> updatedArtifacts = new LinkedHashMap<>();
        for (String artifactName : activeArtifacts) {
            ArtifactAggregation artifactAggregation = aggregateArtifact(
                    artifactName, byArtifact.getOrDefault(artifactName, List.of()));
            updatedArtifacts.put(artifactName, artifactAggregation.artifact());
            accumulator.add(artifactName, artifactAggregation);
        }

        stableIdIndex.save();
        for (Map.Entry<String, CanonicalArtifact> entry : updatedArtifacts.entrySet()) {
            canonicalRepository.write(entry.getKey(), entry.getValue());
        }
        for (IngestionRecord record : records) {
            ledger.markAggregated(record.key());
        }
        return accumulator.toResult();
    }

    private List<IngestionRecord> readyRecords() {
        return ledger.findReadyForAggregation().stream()
                .sorted(Comparator
                        .comparing(IngestionRecord::detectedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(record -> record.key().value()))
                .toList();
    }

    private Map<String, List<PartitionArtifact>> groupByArtifact(List<PartitionArtifact> partitions) {
        return partitions.stream()
                .collect(Collectors.groupingBy(
                        PartitionArtifact::artifactName,
                        LinkedHashMap::new,
                        Collectors.toList()));
    }

    private ArtifactAggregation aggregateArtifact(String artifactName, List<PartitionArtifact> partitions) {
        CanonicalArtifact canonical = canonicalRepository.load(artifactName);
        Map<ArtifactRowKey, ArtifactRow> rowsByKey = new LinkedHashMap<>();
        List<ArtifactRow> outputRows = new ArrayList<>(canonical.rows());
        int skipped = 0;

        for (ArtifactRow row : canonical.rows()) {
            var key = identityResolver.keyOf(artifactName, row);
            if (key.isPresent()) {
                rowsByKey.putIfAbsent(key.get(), row);
            } else {
                skipped++;
            }
        }

        int read = 0;
        int unchanged = 0;
        int newIds = 0;
        for (PartitionArtifact partition : partitions) {
            for (ArtifactRow row : partition.rows()) {
                read++;
                var key = identityResolver.keyOf(artifactName, row);
                if (key.isEmpty()) {
                    skipped++;
                    continue;
                }
                ArtifactRow existing = rowsByKey.get(key.get());
                if (existing != null) {
                    rowsByKey.put(key.get(), mergePolicy.merge(existing, row));
                    unchanged++;
                    continue;
                }
                StableArtifactId id = stableIdIndex.getOrCreate(artifactName, key.get());
                if (id.newlyCreated()) {
                    newIds++;
                }
                ArtifactRow withId = row.withValue("id", Long.toString(id.value()));
                rowsByKey.put(key.get(), withId);
                outputRows.add(withId);
            }
        }

        return new ArtifactAggregation(
                new CanonicalArtifact(artifactName, canonical.header(), outputRows),
                read,
                outputRows.size(),
                newIds,
                unchanged,
                skipped);
    }

    private record ArtifactAggregation(CanonicalArtifact artifact,
                                       int rowsRead,
                                       int rowsWritten,
                                       int newStableIds,
                                       int unchangedRows,
                                       int skippedRows) {
    }

    private static final class ResultAccumulator {
        private final int sourceCount;
        private final int partitionCount;
        private final Map<String, Integer> rowsRead = new HashMap<>();
        private final Map<String, Integer> rowsWritten = new HashMap<>();
        private int newStableIds;
        private int unchangedRows;
        private int skippedRows;

        private ResultAccumulator(int sourceCount, int partitionCount) {
            this.sourceCount = sourceCount;
            this.partitionCount = partitionCount;
        }

        private void add(String artifactName, ArtifactAggregation aggregation) {
            rowsRead.put(artifactName, aggregation.rowsRead());
            rowsWritten.put(artifactName, aggregation.rowsWritten());
            newStableIds += aggregation.newStableIds();
            unchangedRows += aggregation.unchangedRows();
            skippedRows += aggregation.skippedRows();
        }

        private AggregationResult toResult() {
            return new AggregationResult(sourceCount, partitionCount, rowsRead, rowsWritten,
                    newStableIds, unchangedRows, skippedRows);
        }
    }
}
