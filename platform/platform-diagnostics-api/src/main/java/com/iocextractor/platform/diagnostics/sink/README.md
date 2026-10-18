# Diagnostic delivery

`DiagnosticSink.emit` is synchronous. `NoopDiagnosticSink.INSTANCE` discards
non-null input; `CollectingDiagnosticSink` keeps an unbounded ordered list for
tests and reports and is not thread-safe.

`ResilientDiagnosticSink` calls an application-supplied delegate and failure
callback. It contains runtime exceptions from both, without retry or recursion;
JVM Errors propagate. Null input is rejected before callbacks. Callbacks control
latency and resource ownership; the wrapper owns no workers or persistent queue.
Logging and redaction belong in a consumer adapter.
