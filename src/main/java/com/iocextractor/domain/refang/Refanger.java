package com.iocextractor.domain.refang;

/**
 * Outbound-agnostic domain service: turns defanged text (hxxp, [.], [:], …)
 * back into parseable IOCs. Pure function, no I/O.
 */
public interface Refanger {

    String refang(String text);
}
