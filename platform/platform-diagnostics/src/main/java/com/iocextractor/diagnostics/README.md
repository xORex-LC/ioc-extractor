# com.iocextractor.diagnostics

## Responsibility and source map

IOC-owned metadata and exception types over the reusable diagnostics API.
[package-info.java](package-info.java) describes this ownership boundary.

| Source / package | Responsibility |
|---|---|
| [IocDiagnosticCode](IocDiagnosticCode.java) | Shared code contract plus IOC category, message key and English fallback template |
| [DiagnosticCategory](DiagnosticCategory.java) | Product subsystem vocabulary |
| [DiagnosticContextKeys](DiagnosticContextKeys.java) | Context names shared by producers and formatters |
| [DiagnosticException](DiagnosticException.java) | Shared diagnostic carried through the product's unchecked root exception |
| [codes](codes/README.md) | Concrete subsystem enums |
| [catalog](catalog/README.md) | Explicit code registration and flat reference entries |
| [render](render/README.md) | Text rendering without logging or policy enforcement |

Dependencies point to JDK, `platform-diagnostics-api` and `platform-errors`.
The generic `DiagnosticCode` has only ID, default severity and impact; IOC message
metadata is available through `IocDiagnosticCode`. Consumers distinguish ownership
by type, not by guessing from an ID prefix. A generic code has no invented IOC
category and is not automatically included in the product catalog.

## Context and exception contracts

`DiagnosticContextKeys` is a shared vocabulary, not an exhaustive context schema,
allowlist or sanitizer. Code templates can require other contextual names.
Producers supply safe values and own their lifetime under the shared API contract.

`DiagnosticException` requires a non-null diagnostic, retains that same object and
passes its optional cause to `IocExtractorException`. Its message is the literal
code ID, with the diagnostic context map appended when nonempty. Construction
does not evaluate severity/failure policy or invoke a renderer, formatter or sink.
It does not require the supplied code to implement IOC metadata. The caller owns
whether throwing is appropriate at that boundary and whether message/cause output
is safe; formatter-only redaction does not rewrite this exception.

See [exception tests](../../../../../test/java/com/iocextractor/diagnostics/DiagnosticExceptionTest.java)
and [observability](../../../../../../../../docs/dev/observability.md) for evidence and channel ownership.
The [module README](../../../../../../README.md) owns dependencies,
build commands and capability navigation.
