package com.iocextractor.adapter.processing.camel.compile;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.camel.Processor;

/** Startup-owned registry of local operation and destination implementations. */
public record OperationCatalog(Map<String, Processor> operations,
                               Map<String, Processor> destinations,
                               Map<String, Set<String>> predicateArguments) {
    public OperationCatalog {
        operations = Map.copyOf(operations);
        destinations = Map.copyOf(destinations);
        predicateArguments = predicateArguments.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                        entry -> Set.copyOf(entry.getValue())));
    }

    @Override public Map<String, Set<String>> predicateArguments() {
        return Map.copyOf(predicateArguments);
    }
}
