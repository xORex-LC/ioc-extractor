package com.iocextractor.domain.refang;

/**
 * Outbound-agnostic domain service: turns defanged text (hxxp, [.], [:], …)
 * back into parseable IOCs. Pure function, no I/O.
 */
public interface Refanger {

    /**
     * Applies configured rules and exposes both transformed text and decision facts.
     *
     * @param text non-null source text
     * @return immutable refang outcome
     */
    RefangOutcome refang(String text);
}
