package com.iocextractor.application.sync;

import com.iocextractor.application.port.in.sync.RemoteFetchCommand;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;

final class RemoteFetchSources {

    private RemoteFetchSources() {
    }

    static List<RemoteFetchSource> selected(List<RemoteFetchSource> sources, RemoteFetchCommand command) {
        List<RemoteFetchSource> matches = sources.stream()
                .filter(source -> command.source()
                        .map(selected -> source.sourceId().equals(selected))
                        .orElse(true))
                .filter(source -> command.endpoint()
                        .map(selected -> source.endpoint().equals(selected))
                        .orElse(true))
                .toList();
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("No sync fetch source matches selection");
        }
        return matches;
    }

    static boolean matches(RemoteFetchSource source, RemoteObject object) {
        SourceMatchers matchers = compileMatchers(source);
        Path leaf = Path.of(leafName(object.path()));
        boolean accepted = matchers.included().isEmpty()
                || matchers.included().stream().anyMatch(matcher -> matcher.matches(leaf));
        return accepted && matchers.excluded().stream().noneMatch(matcher -> matcher.matches(leaf));
    }

    static String leafName(String remotePath) {
        int slash = Math.max(remotePath.lastIndexOf('/'), remotePath.lastIndexOf('\\'));
        String leaf = slash >= 0 ? remotePath.substring(slash + 1) : remotePath;
        if (leaf.isBlank() || leaf.equals(".") || leaf.equals("..")
                || leaf.contains("/") || leaf.contains("\\")) {
            throw new IllegalArgumentException("remote path must end with one safe file name: " + remotePath);
        }
        return leaf;
    }

    private static SourceMatchers compileMatchers(RemoteFetchSource source) {
        return new SourceMatchers(
                source.include().stream().map(RemoteFetchSources::glob).toList(),
                source.exclude().stream().map(RemoteFetchSources::glob).toList());
    }

    private static PathMatcher glob(String pattern) {
        return FileSystems.getDefault().getPathMatcher("glob:" + pattern);
    }

    private record SourceMatchers(List<PathMatcher> included, List<PathMatcher> excluded) {
    }
}
