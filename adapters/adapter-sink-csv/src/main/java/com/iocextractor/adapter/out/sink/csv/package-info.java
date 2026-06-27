/**
 * Commons CSV outbound adapters for canonical projections and immutable export slices.
 *
 * <p>The package owns CSV byte formatting and the local staging/publication protocol. It may
 * implement application ports but must not coordinate export-ledger state or access service DB.
 */
package com.iocextractor.adapter.out.sink.csv;
