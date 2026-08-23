package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.application.export.ExportRun;
import com.iocextractor.application.export.SliceArtifactManifest;
import com.iocextractor.application.export.SliceManifest;
import com.iocextractor.application.port.out.export.SliceManifestCodec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Verifies manifest identity, exact directory membership and every content hash. */
final class SliceTreeVerifier {

    static final String MANIFEST_FILE = "manifest.json";
    static final String SUCCESS_FILE = "_SUCCESS";

    private final SliceManifestCodec codec;

    SliceTreeVerifier(SliceManifestCodec codec) {
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    VerifiedSlice verify(Path directory, ExportRun run) {
        try {
            requireDirectory(directory);
            Path manifestPath = directory.resolve(MANIFEST_FILE);
            requireRegularFile(manifestPath);
            byte[] manifestBytes = Files.readAllBytes(manifestPath);
            String manifestHash = SliceHashes.sha256(manifestBytes);
            SliceManifest manifest = decode(manifestBytes);
            verifyIdentity(manifest, manifestHash, run);
            Set<String> expected = verifyArtifacts(directory, manifest.artifacts());
            expected.add(MANIFEST_FILE);

            Path successPath = directory.resolve(SUCCESS_FILE);
            boolean successPresent = Files.exists(successPath, LinkOption.NOFOLLOW_LINKS);
            if (successPresent) {
                requireRegularFile(successPath);
                String marker = Files.readString(successPath, StandardCharsets.US_ASCII);
                if (!marker.equals(manifestHash + "\n")) {
                    throw invalid("_SUCCESS does not contain the exact manifest SHA-256");
                }
                expected.add(SUCCESS_FILE);
            }
            verifyExactMembers(directory, expected);
            return new VerifiedSlice(manifestHash, manifest, successPresent);
        } catch (InvalidSliceException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw invalid("slice verification failed: " + reason(e), e);
        }
    }

    /** Verifies a published slice using only its self-describing integrity chain. */
    VerifiedSlice verifyAvailable(Path directory) {
        VerifiedSlice verified = verify(directory, null);
        if (!verified.successPresent()) {
            throw invalid("available slice has no _SUCCESS marker");
        }
        return verified;
    }

    private SliceManifest decode(byte[] bytes) {
        try {
            return codec.decode(bytes);
        } catch (RuntimeException e) {
            throw invalid("manifest cannot be decoded: " + reason(e), e);
        }
    }

    private void verifyIdentity(SliceManifest manifest, String manifestHash, ExportRun run) {
        if (run == null) {
            return;
        }
        if (!manifest.runId().equals(run.runId()) || !manifest.sliceId().equals(run.runId())) {
            throw invalid("manifest run/slice identity does not match export run");
        }
        if (!manifest.profile().equals(run.profile())) {
            throw invalid("manifest profile does not match export run");
        }
        if (!manifest.planHash().equals(run.planHash())) {
            throw invalid("manifest plan hash does not match export run");
        }
        if (run.manifestSha256() != null && !run.manifestSha256().equals(manifestHash)) {
            throw invalid("manifest SHA-256 does not match export ledger state");
        }
    }

    private Set<String> verifyArtifacts(Path directory, List<SliceArtifactManifest> artifacts)
            throws IOException {
        Set<String> expected = new LinkedHashSet<>();
        Set<String> artifactNames = new HashSet<>();
        for (SliceArtifactManifest artifact : artifacts) {
            String fileName = safeLeaf(artifact.fileName());
            if (!expected.add(fileName)) {
                throw invalid("duplicate manifest file name: " + fileName);
            }
            if (!artifactNames.add(artifact.artifactName())) {
                throw invalid("duplicate manifest artifact: " + artifact.artifactName());
            }
            Path file = directory.resolve(fileName);
            requireRegularFile(file);
            String actualHash = SliceHashes.sha256(file);
            if (!actualHash.equals(artifact.sha256())) {
                throw invalid("artifact SHA-256 mismatch: " + fileName);
            }
        }
        return expected;
    }

    private void verifyExactMembers(Path directory, Set<String> expected) throws IOException {
        Set<String> actual = new HashSet<>();
        try (var members = Files.list(directory)) {
            members.forEach(path -> actual.add(path.getFileName().toString()));
        }
        if (!actual.equals(expected)) {
            throw invalid("slice directory members differ from manifest: expected="
                    + expected + ", actual=" + actual);
        }
    }

    private void requireDirectory(Path path) {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw invalid("slice path is not a physical directory: " + path);
        }
    }

    private void requireRegularFile(Path path) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw invalid("required physical file is missing: " + path.getFileName());
        }
    }

    private String safeLeaf(String name) {
        if (name == null || name.isBlank() || name.contains("/") || name.contains("\\")
                || name.equals(".") || name.equals("..")) {
            throw invalid("manifest contains an unsafe file name");
        }
        return name;
    }

    private InvalidSliceException invalid(String message) {
        return new InvalidSliceException(message);
    }

    private InvalidSliceException invalid(String message, Throwable cause) {
        return new InvalidSliceException(message, cause);
    }

    private String reason(Throwable failure) {
        return Objects.toString(failure.getMessage(), failure.getClass().getSimpleName());
    }
}
