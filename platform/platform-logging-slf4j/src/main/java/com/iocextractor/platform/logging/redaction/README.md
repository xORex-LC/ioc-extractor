# Selected-value log redaction

This JDK-only helper transforms values explicitly selected by a caller. It has
no severity-dependent bypass, field registry, automatic secret detection or
whole-event scrubbing. See the
[module reference](../../../../../../../../README.md) and
[package-info.java](package-info.java) for the surrounding boundary.

## Source and representations

[LogRedaction.java](LogRedaction.java) exposes two operations:

| Operation | Representation and limit |
|---|---|
| `fullMask()` | Exactly `[redacted]`, without accepting, stringifying or retaining a secret |
| `urlComponents(String)` | Lexically mask recognized URL user-info and query content with `<redacted>`; null returns null |

The URL helper recognizes user-info after `://`, through the last `@` before
`/`, `?` or `#`. It masks content after the first `?` until the following `#` or
end of input. This is not URI parsing/normalization. Scheme-less credentials,
paths and fragments may remain visible; a question mark inside a fragment is
still processed. The [consumer guide](../../../../../../../../../../docs/guides/libraries/logging-slf4j.md#record-a-failure-with-a-selected-url-transformation)
and [LogRedactionTest](../../../../../../../test/java/com/iocextractor/platform/logging/LogRedactionTest.java)
own executable examples, including malformed/partial inputs.

Masks are STRING representations. They do not widen LONG/BOOLEAN fields or
change the field's declared type. Null omission is distinct from a mask and leaves
same-key ambient MDC visible; explicitly hide ambient context when needed.

The caller decides which data can be disclosed. These helpers rewrite neither
other fields nor free messages, MDC, Throwable messages/causes/suppressed errors.
The IOC severity-dependent fingerprint policy lives in the separate
[diagnostics bridge](../../../../../../../../../platform-diagnostics-logging/README.md). It must not
be inferred from this generic helper. Use the
[test map](../../../../../../../test/java/com/iocextractor/platform/logging/README.md)
for masking versus provider/backend qualification.
