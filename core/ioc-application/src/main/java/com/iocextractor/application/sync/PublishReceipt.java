package com.iocextractor.application.sync;

/**
 * Result reported by a transport after an atomic publish attempt is verified.
 */
public record PublishReceipt(String remotePath, String verification) {

    public PublishReceipt {
        if (remotePath == null || remotePath.isBlank()) {
            throw new IllegalArgumentException("remotePath must not be blank");
        }
        if (verification == null || verification.isBlank()) {
            throw new IllegalArgumentException("verification must not be blank");
        }
    }
}
