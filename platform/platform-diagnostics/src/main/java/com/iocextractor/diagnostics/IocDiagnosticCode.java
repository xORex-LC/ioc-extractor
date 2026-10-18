package com.iocextractor.diagnostics;

import com.iocextractor.platform.diagnostics.DiagnosticCode;

/** IOC catalog metadata used for rendering, ownership checks and generated reference tables. */
public interface IocDiagnosticCode extends DiagnosticCode {
    /** @return the IOC subsystem that owns this condition */
    DiagnosticCategory category();

    /** @return the stable message lookup key */
    String messageKey();

    /** @return the English fallback message, with optional context placeholders */
    String defaultMessageTemplate();
}
