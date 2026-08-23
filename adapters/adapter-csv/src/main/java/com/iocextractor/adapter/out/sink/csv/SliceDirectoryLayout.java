package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.application.export.ExportRun;

import java.nio.file.Path;
import java.util.Objects;

/** Resolves safe staging/final paths without accepting path segments from manifests. */
final class SliceDirectoryLayout {

    private final Path root;

    SliceDirectoryLayout(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    Path root() {
        return root;
    }

    Path stagingParent() {
        return root.resolve(".staging");
    }

    Path staging(ExportRun run) {
        return stagingParent().resolve(segment(run.runId(), "runId"));
    }

    Path profile(ExportRun run) {
        return root.resolve(segment(run.profile(), "profile"));
    }

    Path available(ExportRun run) {
        return profile(run).resolve(segment(run.sliceName(), "sliceName"));
    }

    private String segment(String value, String role) {
        if (value == null || value.isBlank()
                || value.contains("/") || value.contains("\\")
                || value.equals(".") || value.equals("..")) {
            throw new IllegalArgumentException(role + " must be one safe path segment");
        }
        return value;
    }
}
