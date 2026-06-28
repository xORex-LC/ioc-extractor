/**
 * Picocli driving adapters that translate command-line arguments into application use-case calls.
 *
 * <p>Commands may resolve primary ports lazily but must not coordinate storage adapters or contain
 * business policy.
 */
package com.iocextractor.adapter.in.cli;
