/**
 * Commons CSV outbound adapters for canonical projections and immutable export slices.
 *
 * <p>The package owns CSV byte formatting and the local staging/publication protocol. It may
 * implement application ports but must not coordinate export-ledger state or access service DB.
 * Completed-slice discovery for remote delivery is read-only and reuses the same immutable slice
 * integrity verifier without sharing retention delete semantics.
 */
package com.iocextractor.adapter.out.sink.csv;
