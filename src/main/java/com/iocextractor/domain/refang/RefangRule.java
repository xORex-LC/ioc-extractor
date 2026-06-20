package com.iocextractor.domain.refang;

/**
 * A single literal de-obfuscation substitution, applied in declared order.
 * Literal (not regex) on purpose: defang tokens like {@code [.]} are fixed strings.
 *
 * @param from obfuscated token, e.g. {@code "hxxp"}, {@code "[.]"}, {@code "[:]"}
 * @param to   refanged replacement, e.g. {@code "http"}, {@code "."}, {@code ":"}
 */
public record RefangRule(String from, String to) {
}
