package com.iocextractor.adapter.in.ingest;

import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.ingest.SourceKey;
import com.iocextractor.application.ingest.admission.DocumentAdmission;
import com.iocextractor.application.ingest.admission.DocumentAdmissionPhase;
import com.iocextractor.application.ingest.admission.DocumentAdmissionReservation;
import com.iocextractor.application.ingest.admission.DocumentCandidateEvidence;
import com.iocextractor.application.ingest.admission.DocumentTerminalOutcome;
import com.iocextractor.application.observation.ObservationOrder;
import com.iocextractor.application.observation.ObservationOrigin;
import com.iocextractor.application.observation.RegisteredObservation;
import com.iocextractor.application.port.out.ingest.DocumentAdmissionJournal;
import com.iocextractor.common.IocExtractorException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

/** Atomic fsync-backed document admission journal for the daemon file-ledger mode. */
public final class FileDocumentAdmissionJournal implements DocumentAdmissionJournal {

    private final Path directory;

    public FileDocumentAdmissionJournal(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
    }

    @Override
    public synchronized DocumentAdmission reserve(DocumentAdmissionReservation reservation) {
        Objects.requireNonNull(reservation, "reservation");
        Optional<DocumentAdmission> existing = find(reservation.observationId());
        if (existing.isPresent()) {
            requireSameReservation(existing.orElseThrow(), reservation);
            return existing.orElseThrow();
        }
        boolean candidateActive = all().stream()
                .anyMatch(value -> value.phase() != DocumentAdmissionPhase.TERMINAL
                        && value.candidatePath().equals(reservation.candidatePath()));
        if (candidateActive) {
            throw new IllegalStateException("Document candidate already has an active reservation");
        }
        DocumentAdmission created = DocumentAdmission.reserved(reservation);
        write(created);
        return created;
    }

    @Override
    public synchronized Optional<DocumentAdmission> find(ObservationId observationId) {
        Path path = pathFor(Objects.requireNonNull(observationId, "observationId"));
        return Files.exists(path) ? Optional.of(read(path)) : Optional.empty();
    }

    @Override
    public synchronized boolean replace(DocumentAdmission expected, DocumentAdmission updated) {
        if (!expected.observationId().equals(updated.observationId())
                || updated.version() != expected.version() + 1) {
            throw new IllegalArgumentException("Document admission replacement must advance one version");
        }
        Optional<DocumentAdmission> current = find(expected.observationId());
        if (current.isEmpty() || !current.orElseThrow().equals(expected)) {
            return false;
        }
        write(updated);
        return true;
    }

    @Override
    public synchronized List<DocumentAdmission> findRecoverable(int limit) {
        requireLimit(limit);
        return all().stream()
                .filter(value -> value.phase() != DocumentAdmissionPhase.TERMINAL
                        || !value.registrationFinalized())
                .sorted(Comparator.comparing(DocumentAdmission::createdAt)
                        .thenComparing(value -> value.observationId().value()))
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized List<DocumentAdmission> findTerminalBefore(Instant cutoff, int limit) {
        Objects.requireNonNull(cutoff, "cutoff");
        requireLimit(limit);
        return all().stream()
                .filter(value -> value.phase() == DocumentAdmissionPhase.TERMINAL)
                .filter(DocumentAdmission::registrationFinalized)
                .filter(value -> value.updatedAt().isBefore(cutoff))
                .sorted(Comparator.comparing(DocumentAdmission::updatedAt)
                        .thenComparing(value -> value.observationId().value()))
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized boolean purgeTerminal(ObservationId observationId, long expectedVersion) {
        Optional<DocumentAdmission> current = find(observationId);
        if (current.isEmpty()) {
            return false;
        }
        DocumentAdmission admission = current.orElseThrow();
        if (admission.phase() != DocumentAdmissionPhase.TERMINAL
                || !admission.registrationFinalized() || admission.version() != expectedVersion) {
            return false;
        }
        try {
            boolean deleted = Files.deleteIfExists(pathFor(observationId));
            if (deleted) {
                forceDirectory();
            }
            return deleted;
        } catch (IOException failure) {
            throw new IocExtractorException("Failed to purge document admission", failure);
        }
    }

    private List<DocumentAdmission> all() {
        if (!Files.exists(directory)) {
            return List.of();
        }
        try (var files = Files.list(directory)) {
            return files.filter(this::isJournalFile)
                    .map(this::read)
                    .toList();
        } catch (IOException failure) {
            throw new IocExtractorException("Failed to scan document admission journal", failure);
        }
    }

    private boolean isJournalFile(Path path) {
        Path fileName = path.getFileName();
        return fileName != null && fileName.toString().endsWith(".properties");
    }

    private DocumentAdmission read(Path path) {
        Properties values = new Properties();
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            values.load(reader);
        } catch (IOException failure) {
            throw new IocExtractorException("Failed to read document admission: " + path, failure);
        }
        ObservationId id = new ObservationId(required(values, "observationId"));
        DocumentAdmissionPhase phase = DocumentAdmissionPhase.valueOf(required(values, "phase"));
        Optional<RegisteredObservation> registration = optional(values, "namespace")
                .map(namespace -> new RegisteredObservation(id, namespace,
                        new ObservationOrder(Long.parseLong(required(values, "admissionOrder"))),
                        ObservationOrigin.DOCUMENT));
        Optional<DocumentCandidateEvidence> claimed = optional(values, "claimedSize")
                .map(size -> new DocumentCandidateEvidence(optional(values, "claimedFileKey"),
                        Long.parseLong(size), Long.parseLong(required(values, "claimedMtimeNanos"))));
        return new DocumentAdmission(id, Path.of(required(values, "candidatePath")),
                new DocumentCandidateEvidence(optional(values, "candidateFileKey"),
                        Long.parseLong(required(values, "candidateSize")),
                        Long.parseLong(required(values, "candidateMtimeNanos"))),
                Path.of(required(values, "claimPath")), claimed, phase,
                Long.parseLong(required(values, "version")), registration,
                optional(values, "sourceKey").map(SourceKey::new),
                optional(values, "terminalOutcome").map(DocumentTerminalOutcome::valueOf),
                Boolean.parseBoolean(required(values, "registrationFinalized")),
                Instant.parse(required(values, "createdAt")),
                Instant.parse(required(values, "updatedAt")));
    }

    private void write(DocumentAdmission admission) {
        try {
            Files.createDirectories(directory);
            Properties values = properties(admission);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            values.store(bytes, "ioc document admission");
            Path temporary = Files.createTempFile(directory, token(admission.observationId()), ".tmp");
            try {
                try (FileChannel channel = FileChannel.open(temporary,
                        StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    ByteBuffer buffer = ByteBuffer.wrap(bytes.toByteArray());
                    while (buffer.hasRemaining()) {
                        channel.write(buffer);
                    }
                    channel.force(true);
                }
                Files.move(temporary, pathFor(admission.observationId()),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                forceDirectory();
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException failure) {
            throw new IocExtractorException(
                    "Failed to write document admission: " + admission.observationId().value(), failure);
        }
    }

    private Properties properties(DocumentAdmission admission) {
        Properties values = new Properties();
        values.setProperty("observationId", admission.observationId().value());
        values.setProperty("candidatePath", admission.candidatePath().toString());
        values.setProperty("candidateFileKey", admission.candidateEvidence().fileKey().orElse(""));
        values.setProperty("candidateSize", Long.toString(admission.candidateEvidence().size()));
        values.setProperty("candidateMtimeNanos",
                Long.toString(admission.candidateEvidence().modifiedAtNanos()));
        values.setProperty("claimPath", admission.claimPath().toString());
        values.setProperty("claimedFileKey", admission.claimedEvidence()
                .flatMap(DocumentCandidateEvidence::fileKey).orElse(""));
        values.setProperty("claimedSize", admission.claimedEvidence()
                .map(value -> Long.toString(value.size())).orElse(""));
        values.setProperty("claimedMtimeNanos", admission.claimedEvidence()
                .map(value -> Long.toString(value.modifiedAtNanos())).orElse(""));
        values.setProperty("phase", admission.phase().name());
        values.setProperty("version", Long.toString(admission.version()));
        values.setProperty("namespace", admission.registration()
                .map(RegisteredObservation::namespaceId).orElse(""));
        values.setProperty("admissionOrder", admission.registration()
                .map(value -> Long.toString(value.admissionOrder().value())).orElse(""));
        values.setProperty("sourceKey", admission.sourceKey().map(SourceKey::value).orElse(""));
        values.setProperty("terminalOutcome", admission.terminalOutcome().map(Enum::name).orElse(""));
        values.setProperty("registrationFinalized", Boolean.toString(admission.registrationFinalized()));
        values.setProperty("createdAt", admission.createdAt().toString());
        values.setProperty("updatedAt", admission.updatedAt().toString());
        return values;
    }

    private void forceDirectory() throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private Path pathFor(ObservationId observationId) {
        return directory.resolve(token(observationId) + ".properties");
    }

    private String token(ObservationId observationId) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(observationId.value().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private String required(Properties values, String key) {
        String value = values.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Document admission property is missing: " + key);
        }
        return value;
    }

    private Optional<String> optional(Properties values, String key) {
        return Optional.ofNullable(values.getProperty(key)).filter(value -> !value.isBlank());
    }

    private void requireSameReservation(DocumentAdmission existing,
                                        DocumentAdmissionReservation reservation) {
        if (!existing.candidatePath().equals(reservation.candidatePath())
                || !existing.candidateEvidence().equals(reservation.candidateEvidence())
                || !existing.claimPath().equals(reservation.claimPath())) {
            throw new IllegalStateException("Document occurrence reservation changed on retry");
        }
    }

    private void requireLimit(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("Document admission query limit must be positive");
        }
    }
}
