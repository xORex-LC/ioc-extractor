package com.iocextractor.bootstrap;

import com.iocextractor.adapter.out.store.jdbc.JdbcDocumentProcessingPolicyGate;
import java.util.function.BooleanSupplier;

/** Checks durable and external document intake before exposing a changed daemon policy. */
final class DocumentProcessingPolicyAdmission {
    private DocumentProcessingPolicyAdmission() { }

    static void ensure(LazyServiceStorage storage, String fingerprint,
                       BooleanSupplier ledgerDrained, BooleanSupplier processingFilesDrained) {
        if (storage == null) {
            throw new IllegalStateException("Document processing requires durable service storage in daemon mode");
        }
        storage.migration();
        new JdbcDocumentProcessingPolicyGate(storage.dataSource()).ensure(
                fingerprint,
                () -> ledgerDrained.getAsBoolean() && processingFilesDrained.getAsBoolean());
    }
}
