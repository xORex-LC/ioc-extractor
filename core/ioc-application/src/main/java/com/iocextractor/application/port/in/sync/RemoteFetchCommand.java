package com.iocextractor.application.port.in.sync;

import java.util.Optional;

/** Command for one remote fetch cycle, optionally restricted by source and endpoint. */
public record RemoteFetchCommand(Optional<String> source,
                                 Optional<String> endpoint,
                                 boolean dryRun) {

    public RemoteFetchCommand {
        source = source == null ? Optional.empty() : source;
        endpoint = endpoint == null ? Optional.empty() : endpoint;
        source.ifPresent(value -> requireText(value, "source"));
        endpoint.ifPresent(value -> requireText(value, "endpoint"));
    }

    /** Creates a command spanning every configured source. */
    public RemoteFetchCommand(boolean dryRun) {
        this(Optional.empty(), Optional.empty(), dryRun);
    }

    /** Creates a source-filtered command spanning any matching endpoint. */
    public RemoteFetchCommand(Optional<String> source, boolean dryRun) {
        this(source, Optional.empty(), dryRun);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
