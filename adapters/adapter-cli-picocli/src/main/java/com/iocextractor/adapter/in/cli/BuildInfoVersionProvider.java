package com.iocextractor.adapter.in.cli;

import picocli.CommandLine.IVersionProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Supplies Picocli version output from the packaged application's embedded identity. */
public final class BuildInfoVersionProvider implements IVersionProvider {

    private static final int DISPLAY_COMMIT_LENGTH = 12;

    private final Supplier<ApplicationBuildInfo> buildInfo;

    /** Creates the provider used by Picocli's annotation model. */
    public BuildInfoVersionProvider() {
        this(new ApplicationBuildInfoReader()::read);
    }

    BuildInfoVersionProvider(Supplier<ApplicationBuildInfo> buildInfo) {
        this.buildInfo = buildInfo;
    }

    @Override
    public String[] getVersion() {
        ApplicationBuildInfo build = buildInfo.get();
        List<String> lines = new ArrayList<>();
        lines.add("ioc-extractor " + build.version());
        build.commit().ifPresent(commit -> lines.add("commit: " + abbreviate(commit)));
        build.builtAt().ifPresent(builtAt -> lines.add("built: " + builtAt));
        return lines.toArray(String[]::new);
    }

    private String abbreviate(String commit) {
        return commit.substring(0, Math.min(DISPLAY_COMMIT_LENGTH, commit.length()));
    }
}
