package com.iocextractor.application.port.in.export;

/** Forward recovery scoped to a profile whose formation lease is already held. */
@FunctionalInterface
public interface RecoverExportProfileUseCase {

    /** Recovers only this profile; other live formations must never be inspected. */
    int recoverIncomplete(String profile);
}
