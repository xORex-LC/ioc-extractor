package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.application.export.SliceDescriptor;
import com.iocextractor.application.port.out.export.SliceManifestCodec;
import com.iocextractor.application.port.out.export.SliceRetentionStore;
import com.iocextractor.common.IocExtractorException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Local slice retention adapter that treats a verified final directory as one indivisible unit.
 *
 * <p>Only immediate children of a configured profile directory are considered. Staging is outside
 * that scope. Invalid/incomplete final trees fail the sweep and remain untouched, surfacing
 * corruption instead of silently turning it into a retention candidate.
 */
public final class FileSystemSliceRetentionStore implements SliceRetentionStore {

    private final Path root;
    private final SliceTreeVerifier verifier;

    /** Creates a store rooted at the same export directory as the slice writer. */
    public FileSystemSliceRetentionStore(Path root, SliceManifestCodec codec) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.verifier = new SliceTreeVerifier(codec);
    }

    @Override
    public List<SliceDescriptor> listCompleted(String profile) {
        Path profileDir = root.resolve(segment(profile, "profile"));
        if (!Files.exists(profileDir, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try {
            requirePhysicalDirectory(profileDir);
        } catch (InvalidSliceException failure) {
            throw new IocExtractorException(
                    "Failed to scan verified export slices for profile " + profile, failure);
        }
        try (var children = Files.list(profileDir)) {
            List<Path> paths = children.sorted().toList();
            List<SliceDescriptor> slices = new ArrayList<>(paths.size());
            for (Path path : paths) {
                requirePhysicalDirectory(path);
                VerifiedSlice verified = verifier.verifyAvailable(path);
                if (!verified.manifest().profile().equals(profile)) {
                    throw new InvalidSliceException("manifest profile does not match parent directory");
                }
                slices.add(new SliceDescriptor(
                        verified.manifest().sliceId(), profile,
                        path.getFileName().toString(), verified.manifest().createdAt()));
            }
            return List.copyOf(slices);
        } catch (IOException | InvalidSliceException failure) {
            throw new IocExtractorException(
                    "Failed to scan verified export slices for profile " + profile, failure);
        }
    }

    @Override
    public void delete(SliceDescriptor slice) {
        Objects.requireNonNull(slice, "slice");
        Path profileDir = root.resolve(segment(slice.profile(), "profile"));
        Path directory = profileDir.resolve(segment(slice.sliceName(), "sliceName"));
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            VerifiedSlice verified = verifier.verifyAvailable(directory);
            if (!verified.manifest().sliceId().equals(slice.sliceId())
                    || !verified.manifest().profile().equals(slice.profile())
                    || !verified.manifest().createdAt().equals(slice.createdAt())) {
                throw new InvalidSliceException("slice changed after retention discovery");
            }
            try (var tree = Files.walk(directory)) {
                for (Path path : tree.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        } catch (IOException | InvalidSliceException failure) {
            throw new IocExtractorException("Failed to delete completed export slice " + directory, failure);
        }
    }

    private void requirePhysicalDirectory(Path path) {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new InvalidSliceException("slice path is not a physical directory: " + path);
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
