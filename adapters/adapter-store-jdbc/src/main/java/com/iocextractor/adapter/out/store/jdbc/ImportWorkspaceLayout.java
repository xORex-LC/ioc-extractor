package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportStageReference;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Opaque, traversal-safe per-delivery workspace layout. */
final class ImportWorkspaceLayout {

    private static final String REFERENCE_PREFIX = "sqlite-stage-v1:";

    private final Path root;

    ImportWorkspaceLayout(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    Path root() {
        return root;
    }

    WorkspacePaths paths(ImportDeliveryId deliveryId) {
        String token = sha256(deliveryId.value());
        return new WorkspacePaths(
                root.resolve(token + ".building.db"),
                root.resolve(token + ".sealed.db"),
                new ImportStageReference(REFERENCE_PREFIX + token));
    }

    void requireReference(ImportDeliveryId deliveryId, ImportStageReference reference) {
        if (!paths(deliveryId).reference().equals(reference)) {
            throw new IllegalArgumentException("Import stage reference does not belong to the delivery");
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is not available", failure);
        }
    }

    record WorkspacePaths(Path building, Path sealed, ImportStageReference reference) {
    }
}
