package com.iocextractor.application.port.in.sync;

import java.util.Optional;

/** Command for one remote fetch cycle, optionally restricted to one configured source. */
public record RemoteFetchCommand(Optional<String> source, boolean dryRun) {

    public RemoteFetchCommand {
        source = source == null ? Optional.empty() : source;
        source.ifPresent(value -> requireText(value, "source"));
    }

    /** Creates a command spanning every configured source. */
    public RemoteFetchCommand(boolean dryRun) {
        this(Optional.empty(), dryRun);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
