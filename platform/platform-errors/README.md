# platform-errors

## Purpose and boundary

Internal `com.iocextractor:ioc-platform-errors` is the lowest platform exception
module. It supplies the shared unchecked base for technical extraction/I/O and
boundary failures. It has no production dependency beyond the JDK and no IOC
model, diagnostic catalog, logging or framework policy.

This module is already part of the Maven reactor. The historical package name
`com.iocextractor.common` does not make it a home for unrelated helpers. Changes
here affect higher-level exception consumers across the application.

## Structure and dependency direction

| Location | Responsibility |
|---|---|
| [pom.xml](pom.xml) | Parent-managed internal JAR; no module-specific dependencies or publication profile |
| [com.iocextractor.common](src/main/java/com/iocextractor/common/README.md) | One public base exception and its constructors |

Production code uses only `java.lang.RuntimeException`. This module must not
point back to diagnostics, observability, domain, application, adapters or
bootstrap. Framework-free consumers can depend on it without acquiring a logging
backend or a diagnostic registry. Domain remains independent of platform errors;
[BOUNDARIES](../../docs/BOUNDARIES.md) owns project-wide import/dependency rules.

The IOC [DiagnosticException](../platform-diagnostics/src/main/java/com/iocextractor/diagnostics/DiagnosticException.java)
extends this base and carries a stopping diagnostic. That dependency points from
IOC diagnostics to errors. Generic diagnostic values and policies belong to the
separate shared diagnostic API, not this module. Adapters and bootstrap also use
plain base exceptions for boundaries that do not carry a diagnostic.

## Entry point and policy ownership

`IocExtractorException` offers message-only and message-plus-cause constructors,
with standard Java exception behavior. It does not attach a code, severity,
completion state or retry decision, and construction performs no logging or I/O.
Catching the base can also catch diagnostic subclasses; preserve their typed
payload and original cause when handling failures.

“Unchecked” means Java does not require a throws declaration. It does not imply
that every occurrence is safe to retry, that no durable effect happened, or that
the process must exit. Callers own transaction, retry, completion and rendering
policy; [observability](../../docs/dev/observability.md) explains the diagnostic bridge.
This internal module is not an independently published reusable library.

## Verification

There is no local test source tree. The reactor compiles the base and its
consumers; adapter failure contracts exercise subtype/cause behavior and bootstrap
ArchUnit tests enforce the project boundaries. From the repository root:

```bash
make verify
```

A module-only test command cannot establish cross-module handling or application
recovery. Exception behavior here stays within the standard JDK contract.
