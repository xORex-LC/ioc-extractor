# com.iocextractor.diagnostics.render

## Responsibility and sources

Human-readable diagnostic text without logging, storage or control-flow effects.
[package-info.java](package-info.java) describes the boundary. This package depends
on the shared `Diagnostic` and IOC message metadata, not Spring `MessageSource`
or a logging backend.

| Source | Responsibility |
|---|---|
| [DiagnosticRenderer](DiagnosticRenderer.java) | Render one diagnostic as text |
| [DiagnosticContextFormatter](DiagnosticContextFormatter.java) | Format one present placeholder value with diagnostic/key context |
| [TemplateDiagnosticRenderer](TemplateDiagnosticRenderer.java) | Default-template substitution with an optional caller-supplied formatter |

## Substitution contract

For `IocDiagnosticCode`, rendering uses `defaultMessageTemplate()` and substitutes
`{key}` from context. A missing/null value leaves that placeholder visible; the
formatter is called only for present values referenced by placeholders. Repeated
placeholders are processed individually. Replacement text is inserted literally,
including regex replacement characters; placeholder-looking replacement text is
not recursively expanded. Extra context is not automatically appended.

For a generic `DiagnosticCode`, the entire result is its literal ID. The renderer
does not parse braces in that ID or pass its context through the formatter. There
is no message-key lookup, locale catalog or i18n loading; `messageKey()` remains
metadata for other consumers.

The default formatter uses `String.valueOf` and exposes supplied values unchanged.
An explicit formatter may mask or otherwise format text; it must return a valid
replacement and not mutate the diagnostic. Formatter exceptions propagate. The
renderer itself retains only that shared strategy, so concurrent use also requires
that the supplied formatter be safe to share.

## Disclosure and integration boundary

Formatting changes output text only. It does not rewrite original context, free
messages, exception causes or suppressed exceptions. `DiagnosticException` also
builds its message without using this renderer. Producers own disclosure on all
channels; a formatter is not a universal sanitizer or exception scrubber.

[Bootstrap](../../../../../../../../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/AppConfig.java)
selects the redacting formatter from the outer
[diagnostic logging module](../../../../../../../../platform-diagnostics-logging/README.md).
That module owns IOC masking rules and the logging sink; this module supplies no
default logger or emission side effects. See
[observability disclosure](../../../../../../../../../docs/dev/observability.md#disclosure-and-redaction) for
current masking scope and producer responsibilities.

[TemplateDiagnosticRendererTest](../../../../../../test/java/com/iocextractor/diagnostics/render/TemplateDiagnosticRendererTest.java)
checks ordinary/missing/nonrecursive substitution and generic-code literal fallback.
It is not redaction or actual backend-output qualification. The
[test map](../../../../../../test/java/com/iocextractor/diagnostics/README.md)
and [module README](../../../../../../../README.md) link wider evidence.
