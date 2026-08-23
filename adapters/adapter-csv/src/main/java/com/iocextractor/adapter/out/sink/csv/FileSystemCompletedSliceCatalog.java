package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.application.port.out.export.SliceManifestCodec;
import com.iocextractor.application.port.out.sync.CompletedSliceCatalog;
import com.iocextractor.application.sync.CompletedSlice;
import com.iocextractor.common.IocExtractorException;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.codes.SyncDiagnosticCodes;
import com.iocextractor.diagnostics.sink.DiagnosticSink;
import com.iocextractor.diagnostics.sink.NoopDiagnosticSink;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Filesystem-backed publish worklist over verified immutable export slice directories.
 */
public final class FileSystemCompletedSliceCatalog implements CompletedSliceCatalog {

    private final Path root;
    private final SliceTreeVerifier verifier;
    private final DiagnosticSink diagnosticSink;
    private final DiagnosticFactory diagnosticFactory;

    /** Creates a catalog rooted at the same export directory as the slice writer. */
    public FileSystemCompletedSliceCatalog(Path root, SliceManifestCodec codec) {
        this(root, codec, NoopDiagnosticSink.INSTANCE, new DiagnosticFactory(Clock.systemUTC()));
    }

    /** Creates a catalog with explicit diagnostics for skipped invalid slices. */
    public FileSystemCompletedSliceCatalog(Path root,
                                           SliceManifestCodec codec,
                                           DiagnosticSink diagnosticSink,
                                           DiagnosticFactory diagnosticFactory) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.verifier = new SliceTreeVerifier(codec);
        this.diagnosticSink = Objects.requireNonNull(diagnosticSink, "diagnosticSink");
        this.diagnosticFactory = Objects.requireNonNull(diagnosticFactory, "diagnosticFactory");
    }

    @Override
    public List<CompletedSlice> listCompleted(String profile) {
        String profileSegment = segment(profile, "profile");
        Path profileDir = root.resolve(profileSegment);
        if (!Files.exists(profileDir, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try {
            requirePhysicalDirectory(profileDir);
        } catch (InvalidSliceException failure) {
            throw new IocExtractorException(
                    "Failed to discover completed export slices for profile " + profileSegment, failure);
        }
        try (var children = Files.list(profileDir)) {
            List<Path> paths = children.sorted().toList();
            List<CompletedSlice> slices = new ArrayList<>(paths.size());
            for (Path path : paths) {
                try {
                    slices.add(verify(profileSegment, path));
                } catch (InvalidSliceException failure) {
                    emitInvalidSlice(profileSegment, path.getFileName().toString(), failure);
                }
            }
            return List.copyOf(slices);
        } catch (IOException failure) {
            throw new IocExtractorException(
                    "Failed to discover completed export slices for profile " + profileSegment, failure);
        }
    }

    @Override
    public List<String> listCompletedSliceNames(String profile) {
        String profileSegment = segment(profile, "profile");
        Path profileDir = root.resolve(profileSegment);
        if (!Files.exists(profileDir, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try {
            requirePhysicalDirectory(profileDir);
        } catch (InvalidSliceException failure) {
            throw new IocExtractorException(
                    "Failed to discover completed export slices for profile " + profileSegment, failure);
        }
        try (var children = Files.list(profileDir)) {
            List<Path> paths = children.sorted().toList();
            List<String> sliceNames = new ArrayList<>(paths.size());
            for (Path path : paths) {
                String sliceName = path.getFileName().toString();
                try {
                    requirePhysicalDirectory(path);
                    requireSuccessMarker(path);
                    sliceNames.add(sliceName);
                } catch (InvalidSliceException failure) {
                    emitInvalidSlice(profileSegment, sliceName, failure);
                }
            }
            return List.copyOf(sliceNames);
        } catch (IOException failure) {
            throw new IocExtractorException(
                    "Failed to discover completed export slices for profile " + profileSegment, failure);
        }
    }

    @Override
    public Optional<CompletedSlice> find(String profile, String sliceName) {
        String profileSegment = segment(profile, "profile");
        String sliceSegment = segment(sliceName, "sliceName");
        Path profileDir = root.resolve(profileSegment);
        if (!Files.exists(profileDir, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        Path sliceDir = profileDir.resolve(sliceSegment);
        if (!Files.exists(sliceDir, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try {
            requirePhysicalDirectory(profileDir);
            return Optional.of(verify(profileSegment, sliceDir));
        } catch (InvalidSliceException failure) {
            emitInvalidSlice(profileSegment, sliceSegment, failure);
            throw new IocExtractorException(
                    "Failed to discover completed export slice " + profileSegment + "/" + sliceSegment,
                    failure);
        }
    }

    private CompletedSlice verify(String profileSegment, Path path) {
        requirePhysicalDirectory(path);
        VerifiedSlice verified = verifier.verifyAvailable(path);
        if (!verified.manifest().profile().equals(profileSegment)) {
            throw new InvalidSliceException("manifest profile does not match parent directory");
        }
        return new CompletedSlice(
                verified.manifest().sliceId(),
                profileSegment,
                path.getFileName().toString(),
                verified.manifestSha256(),
                path,
                verified.manifest());
    }

    private void emitInvalidSlice(String profileSegment, String sliceName, InvalidSliceException failure) {
        diagnosticSink.emit(diagnosticFactory.create(SyncDiagnosticCodes.LOCAL_SLICE_INVALID)
                .with("profile", profileSegment)
                .with("sliceName", sliceName)
                .with("reason", Objects.toString(failure.getMessage(), failure.getClass().getSimpleName()))
                .cause(failure)
                .build());
    }

    private void requirePhysicalDirectory(Path path) {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new InvalidSliceException("slice path is not a physical directory: " + path);
        }
    }

    private void requireSuccessMarker(Path path) {
        Path marker = path.resolve(SliceTreeVerifier.SUCCESS_FILE);
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(marker)) {
            throw new InvalidSliceException("completed slice has no physical _SUCCESS marker");
        }
    }

    private String segment(String value, String role) {
        if (value == null || value.isBlank() || value.contains("/") || value.contains("\\")
                || value.equals(".") || value.equals("..")) {
            throw new IllegalArgumentException(role + " must be one safe path segment");
        }
        return value;
    }
}
