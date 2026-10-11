package com.iocextractor.adapter.out.store.jdbc;

import java.util.Map;

/** Validated counts from one dependency-pinned committed receipt snapshot, without copying immutable payload. */
record JdbcConfirmationReceiptBase(String receipt, Map<String, Long> rows) {
    static final JdbcConfirmationReceiptBase EMPTY = new JdbcConfirmationReceiptBase("", Map.of());

    JdbcConfirmationReceiptBase {
        rows = Map.copyOf(rows);
        if (rows.size() > 128 || rows.values().stream().anyMatch(count -> count < 0)) {
            throw new IllegalArgumentException("Invalid committed receipt counts");
        }
    }

    long count(String receiptId, String artifact) {
        return receipt.equals(receiptId) ? rows.getOrDefault(artifact, 0L) : 0;
    }
}
