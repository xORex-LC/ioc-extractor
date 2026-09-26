package com.iocextractor.adapter.in.ingest;

import com.iocextractor.application.ingest.admission.DocumentCandidateEvidence;
import com.iocextractor.common.IocExtractorException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Captures stable filesystem identity evidence without following symbolic links. */
public final class FileDocumentCandidateEvidenceReader {

    public DocumentCandidateEvidence read(Path path) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) {
                throw new IocExtractorException("Document candidate is not a regular file: " + path);
            }
            return new DocumentCandidateEvidence(
                    Optional.ofNullable(attributes.fileKey()).map(Object::toString),
                    attributes.size(), attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS));
        } catch (IOException failure) {
            throw new IocExtractorException("Failed to inspect document candidate: " + path, failure);
        }
    }
}
