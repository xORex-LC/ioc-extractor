package com.iocextractor.adapter.in.ingest;

import com.iocextractor.application.ingest.admission.DocumentAdmission;
import com.iocextractor.application.ingest.admission.DocumentCandidateEvidence;
import com.iocextractor.application.ingest.admission.DocumentRejection;
import com.iocextractor.application.ingest.admission.DocumentExecutionState.Failure;
import com.iocextractor.common.IocExtractorException;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Executes rejection in the daemon's private directories using the persisted file evidence. */
final class DocumentRejectionFiles {
    private static final String REJECTED = "__rejected";
    private static final List<String> LEAF_SUFFIXES = List.of(REJECTED, REJECTED + ".error", REJECTED + ".error.tmp");

    private final Path failedDir;
    private final StrictAtomicFileOwnership ownership;

    DocumentRejectionFiles(Path failedDir, StrictAtomicFileOwnership ownership) {
        this.failedDir = failedDir;
        this.ownership = ownership;
    }

    Path target(String token) {
        return failedDir.resolve(token + REJECTED);
    }

    Optional<String> tokenOf(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        if (!failedDir.equals(absolute.getParent())) {
            return Optional.empty();
        }
        String name = Objects.requireNonNull(absolute.getFileName()).toString();
        return LEAF_SUFFIXES.stream().filter(name::endsWith).findFirst()
                .map(suffix -> name.substring(0, name.length() - suffix.length()))
                .filter(token -> !token.isEmpty());
    }

    void finish(DocumentAdmission admission, Path expectedClaim, Path expectedTarget,
                FileDocumentCandidateEvidenceReader reader) {
        DocumentRejection intent = admission.rejection().orElseThrow();
        Path source = intent.source();
        Path target = intent.target();
        if (!target.equals(expectedTarget)
                || !source.equals(expectedClaim) && !source.equals(sealed(expectedClaim))) {
            throw new IllegalStateException("Document rejection paths differ from configured ownership");
        }
        Path processingDir = Objects.requireNonNull(expectedClaim.getParent());
        try {
            validateSealFiles(admission, source, reader);
            if (present(source)) {
                if (present(target) || !intent.evidence().sameObjectAs(reader.read(source))) {
                    throw new DocumentOwnershipException(Failure.REJECTION_TARGET_CONFLICT);
                }
                ownership.claim(source, target);
            }
            if (!present(target)) {
                throw new DocumentOwnershipException(Failure.REJECTION_FILES_MISSING);
            }
            if (!intent.evidence().sameObjectAs(reader.read(target))) {
                throw new DocumentOwnershipException(Failure.REJECTION_TARGET_CONFLICT);
            }
            if (source.equals(sealed(expectedClaim))) {
                Files.deleteIfExists(expectedClaim);
            }
            Files.deleteIfExists(sibling(expectedClaim, ".sealing"));
            forceDirectory(processingDir);
            writeSidecar(target, intent.code().name() + "\n");
            forceDirectory(failedDir);
        } catch (IOException failure) {
            throw new IocExtractorException("Document rejection filesystem operation failed", failure);
        }
    }

    private void validateSealFiles(DocumentAdmission admission, Path source,
                                   FileDocumentCandidateEvidenceReader reader) {
        Path raw = admission.claimPath();
        Path other = source.equals(raw) ? sealed(raw) : raw;
        if (present(other)) {
            if (!other.equals(raw) || !admission.candidateEvidence().sameObjectAs(reader.read(other))) {
                throw new IllegalStateException("Document rejection found another private source");
            }
        }
        Path temporary = sibling(raw, ".sealing");
        if (present(temporary)) {
            reader.read(temporary);
        }
    }

    private void writeSidecar(Path target, String content) throws IOException {
        Path sidecar = sibling(target, ".error");
        if (present(sidecar) && !Files.isRegularFile(sidecar, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Document rejection sidecar is occupied");
        }
        Path temporary = sibling(target, ".error.tmp");
        // A previous attempt can leave this private temporary file incomplete.
        if (present(temporary)) {
            if (!Files.isRegularFile(temporary, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("Document rejection sidecar temporary path is occupied");
            }
            Files.delete(temporary);
        }
        Files.writeString(temporary, content, StandardOpenOption.CREATE_NEW);
        try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        Files.move(temporary, sidecar, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    static Path sealed(Path claim) { return sibling(claim, ".sealed"); }

    static Path sibling(Path path, String suffix) {
        return path.resolveSibling(path.getFileName() + suffix);
    }

    static boolean present(Path path) {
        return !Files.notExists(path, LinkOption.NOFOLLOW_LINKS);
    }

    private static void forceDirectory(Path directory) throws IOException {
        try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }
}
