package com.iocextractor.adapter.in.ingest;

import org.springframework.integration.file.filters.FileListFilter;

import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * Include/exclude and quiet-period filter for whole-file ingestion.
 */
public final class IngestFileListFilter implements FileListFilter<File> {

    private final List<PathMatcher> include;
    private final List<PathMatcher> exclude;
    private final Duration quietPeriod;
    private final Clock clock;

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
        return List.of(files).stream()
                .filter(this::accept)
                .toList();
    }

    @Override
    public boolean accept(File file) {
        if (!file.isFile()) {
            return false;
        }
        var name = file.toPath().getFileName();
        boolean included = include.stream().anyMatch(matcher -> matcher.matches(name));
        boolean excluded = exclude.stream().anyMatch(matcher -> matcher.matches(name));
        long age = clock.millis() - file.lastModified();
        return included && !excluded && age >= quietPeriod.toMillis();
    }

    private List<PathMatcher> matchers(List<String> patterns) {
        return patterns.stream()
                .map(pattern -> FileSystems.getDefault().getPathMatcher("glob:" + pattern))
                .toList();
    }
}
