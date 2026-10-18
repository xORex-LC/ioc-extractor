# IOC diagnostic metadata

This package owns `IocDiagnosticCode`, `DiagnosticCategory`,
`DiagnosticContextKeys` and `DiagnosticException`. `codes/` defines product enums,
`catalog/` enumerates them for generated documentation, and `render/` formats
messages. Generic values and collectors moved to
`com.iocextractor.platform.diagnostics` in `ioc-platform-diagnostics-api`.

Every IOC code implements the shared three-member code contract plus category,
message key and template. Ownership checks use `instanceof IocDiagnosticCode`;
they never invent an IOC category for a generic consumer code. Rendering falls
back to the literal ID when product metadata is absent.

Dependencies: JDK, the diagnostics API and `ioc-platform-errors`. Logging,
redaction configuration and failure-policy enforcement belong to callers.
