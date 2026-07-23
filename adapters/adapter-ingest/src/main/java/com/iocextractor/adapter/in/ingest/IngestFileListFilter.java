package com.iocextractor.adapter.in.ingest;

import org.springframework.integration.file.filters.DiscardAwareFileListFilter;

import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Include/exclude and quiet-period filter for whole-file ingestion.
 *
 * <p>Matching files rejected only because they are still inside the quiet
 * period are reported through the discard-aware callback. Spring Integration's
 * WatchService scanner uses that callback to retry the same filesystem event
 * on a later poll. Permanently excluded files are not retained for retry.
 */
public final class IngestFileListFilter implements DiscardAwareFileListFilter<File> {

    private final List<PathMatcher> include;
    private final List<PathMatcher> exclude;
    private final Duration quietPeriod;
    private final Clock clock;
    private final List<Consumer<File>> discardCallbacks = new CopyOnWriteArrayList<>();

    public IngestFileListFilter(List<String> include, List<String> exclude, Duration quietPeriod, Clock clock) {
        this.include = matchers(include == null || include.isEmpty() ? List.of("*") : include);
        this.exclude = matchers(exclude == null ? List.of() : exclude);
        this.quietPeriod = quietPeriod == null ? Duration.ZERO : quietPeriod;
        this.clock = clock;
    }

    @Override
    public List<File> filterFiles(File[] files) {
        if (files == null || files.length == 0) {
            return List.of();
        }
        var accepted = new ArrayList<File>(files.length);
        for (File file : files) {
            switch (evaluate(file)) {
                case ACCEPT -> accepted.add(file);
                case RETRY -> discardCallbacks.forEach(callback -> callback.accept(file));
                case REJECT -> {
                    // Permanently excluded files must not stay in a WatchService retry set.
                }
            }
        }
        return List.copyOf(accepted);
    }

    @Override
    public boolean accept(File file) {
        return evaluate(file) == Decision.ACCEPT;
    }

    @Override
    public void addDiscardCallback(Consumer<File> discardCallback) {
        discardCallbacks.add(Objects.requireNonNull(discardCallback, "discardCallback"));
    }

    private Decision evaluate(File file) {
        Objects.requireNonNull(file, "file");
        if (!file.isFile()) {
            return Decision.REJECT;
        }
        var name = file.toPath().getFileName();
        boolean included = include.stream().anyMatch(matcher -> matcher.matches(name));
        boolean excluded = exclude.stream().anyMatch(matcher -> matcher.matches(name));
        if (!included || excluded) {
            return Decision.REJECT;
        }
        long age = clock.millis() - file.lastModified();
        return age >= quietPeriod.toMillis() ? Decision.ACCEPT : Decision.RETRY;
    }

    private List<PathMatcher> matchers(List<String> patterns) {
        return patterns.stream()
                .map(pattern -> FileSystems.getDefault().getPathMatcher("glob:" + pattern))
                .toList();
    }

    private enum Decision {
        ACCEPT,
        RETRY,
        REJECT
    }
}
