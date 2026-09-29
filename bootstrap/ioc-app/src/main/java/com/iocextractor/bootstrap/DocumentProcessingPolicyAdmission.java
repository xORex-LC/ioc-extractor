package com.iocextractor.bootstrap;

import com.iocextractor.adapter.out.store.jdbc.JdbcDocumentProcessingPolicyGate;
import java.util.function.BooleanSupplier;

/** Checks durable and external document intake before exposing a changed daemon policy. */
final class DocumentProcessingPolicyAdmission {
    private DocumentProcessingPolicyAdmission() { }

    static void ensure(LazyServiceStorage storage, String fingerprint, boolean selected,
                       BooleanSupplier ledgerDrained, BooleanSupplier processingFilesDrained) {
        if (storage == null) {
            if (selected) {
                throw new IllegalStateException(
                        "Document processing plan requires durable service storage in daemon mode");
            }
            return;
        }
        storage.migration();
        new JdbcDocumentProcessingPolicyGate(storage.dataSource()).ensure(
                fingerprint, selected,
                () -> ledgerDrained.getAsBoolean() && processingFilesDrained.getAsBoolean());
    }
}
