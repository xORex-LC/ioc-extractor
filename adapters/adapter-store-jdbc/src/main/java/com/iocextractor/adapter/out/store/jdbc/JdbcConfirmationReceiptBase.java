package com.iocextractor.adapter.out.store.jdbc;

import java.util.Map;

/** Validated counts from one dependency-pinned committed receipt snapshot, without copying immutable payload. */
record JdbcConfirmationReceiptBase(String receipt, Map<String, Long> rows) {
    static final JdbcConfirmationReceiptBase EMPTY = new JdbcConfirmationReceiptBase("", Map.of());

    JdbcConfirmationReceiptBase {
        rows = Map.copyOf(rows);
    }

    long count(String receiptId, String artifact) {
        return receipt.equals(receiptId) ? rows.getOrDefault(artifact, 0L) : 0;
    }
}
