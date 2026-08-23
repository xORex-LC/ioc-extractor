package com.iocextractor.application.dataframeimport.mapping;

import com.iocextractor.application.dataframeimport.contract.DataframeImportCatalogDraft;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntFunction;

/** Shared exact header normalization used by recognition and the CSV adapter. */
public record ImportHeaderPlan(List<String> canonicalByIndex, Set<String> ignored) {

    /** Snapshots the resolved physical-to-canonical mapping. */
    public ImportHeaderPlan {
        canonicalByIndex = List.copyOf(Objects.requireNonNull(canonicalByIndex, "canonicalByIndex"));
        ignored = Set.copyOf(Objects.requireNonNull(ignored, "ignored"));
    }

    /**
     * Resolves aliases and requires exact declared header membership.
     * Failure text carries counts only and never echoes source header values.
     */
    public static ImportHeaderPlan compile(List<String> external,
                                           DataframeImportCatalogDraft.Recognition recognition) {
        Objects.requireNonNull(external, "external headers");
        Objects.requireNonNull(recognition, "recognition");
        Set<String> allowed = new LinkedHashSet<>();
        allowed.addAll(recognition.requiredColumns());
        allowed.addAll(recognition.optionalColumns());
        allowed.addAll(recognition.ignoredColumns());
        Set<String> ignored = Set.copyOf(recognition.ignoredColumns());
        Set<String> present = new HashSet<>();
        List<String> canonical = new ArrayList<>(external.size());
        int unexpected = 0;
        int duplicates = 0;
        for (String header : external) {
            String resolved = recognition.aliases().getOrDefault(header, header);
            canonical.add(resolved);
            if (!allowed.contains(resolved)) {
                unexpected++;
            } else if (!present.add(resolved)) {
                duplicates++;
            }
        }
        long missing = recognition.requiredColumns().stream()
                .filter(required -> !present.contains(required))
                .count();
        if (unexpected > 0 || duplicates > 0 || missing > 0) {
            throw new IllegalArgumentException(
                    "Delimited input header does not match the configured signature"
                            + " (missing=" + missing
                            + ", unexpected=" + unexpected
                            + ", duplicate=" + duplicates + ")");
        }
        return new ImportHeaderPlan(canonical, ignored);
    }

    /** Maps one physical record into canonical non-ignored source columns. */
    public Map<String, String> values(int physicalSize, IntFunction<String> valueAt) {
        Objects.requireNonNull(valueAt, "valueAt");
        if (physicalSize != canonicalByIndex.size()) {
            throw new IllegalArgumentException("Delimited input row has a different column count than its header");
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < canonicalByIndex.size(); index++) {
            String canonical = canonicalByIndex.get(index);
            if (!ignored.contains(canonical)) {
                values.put(canonical, valueAt.apply(index));
            }
        }
        return values;
    }
}
