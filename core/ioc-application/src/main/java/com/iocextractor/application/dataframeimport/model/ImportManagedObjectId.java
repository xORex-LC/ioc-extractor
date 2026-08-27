package com.iocextractor.application.dataframeimport.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Closed, path-independent name of one delivery-owned managed object.
 *
 * <p>The factory deliberately preserves the original adapter token formula so
 * already claimed objects and pinned snapshot references remain adoptable.</p>
 */
public record ImportManagedObjectId(String value) {

    /** Validates the lowercase SHA-256 token grammar. */
    public ImportManagedObjectId {
        Objects.requireNonNull(value, "value");
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Import managed-object ID must be lowercase SHA-256");
        }
    }

    /** Derives the stable managed-object ID from the exact UTF-8 delivery ID bytes. */
    public static ImportManagedObjectId from(ImportDeliveryId deliveryId) {
        Objects.requireNonNull(deliveryId, "deliveryId");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(deliveryId.value().getBytes(StandardCharsets.UTF_8));
            return new ImportManagedObjectId(HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
