# Source adapter integration tests

Four Failsafe suites exercise the packaged parser integration using synthetic
inputs in JUnit temporary directories. They use the TCK `@IntegrationTest`
annotation; the format suite also has `@ContractTest`. They need no external
server, operator document or deployed service.

| Suite | Observable evidence |
|---|---|
| [TikaSourceReaderCharsetIT.java](TikaSourceReaderCharsetIT.java) | Forced Windows-1251 decodes legacy HTML; a deliberately wrong ASCII choice proves that the override takes effect |
| [TikaSourceReaderDiagnosticIT.java](TikaSourceReaderDiagnosticIT.java) | Unsupported extension/no-extension context, missing input and filesystem-root failure, preserved cause and useful reason for null/empty/blank parser messages |
| [TikaSourceReaderFormatContractIT.java](TikaSourceReaderFormatContractIT.java) | Independently constructed PDF/DOCX/XLSX containers yield sentinel text; shared ZIP helpers build DOCX streaming fixtures |
| [StreamingSourceContractIT.java](StreamingSourceContractIT.java) | Full text equality against a separate default `AutoDetectParser`; HTML repair/EOF/whitespace/supplementary characters and DOCX runs/tables/tabs/hyperlinks/header/footer/footnotes |

Streaming tests compare entire strings, including whitespace and UTF-16 positions,
not just IOC counts. The oracle uses the default parser path, not the adapter's
custom HTML parser or selected SAX-DOCX context. Both paths still share the
installed Tika ecosystem; equivalence on this corpus is not an independent
specification for every malformed document.

The streaming suite also checks many disposable paragraphs, ASCII detection
fallback, excessive depth, dense node graphs, oversized unclosed regions,
`srcdoc`/data attributes, exact-limit EOF and progress reset. Its interruption
case restores the test thread's flag state in `finally` and checks that closing
the reader releases its input. A class-level 20-second `@Timeout` contains it.
The diagnostic suite injects a failing parser for precise boundary behavior;
it does not claim that every unknown extension causes unsupported-format failure
in Tika's real auto-detection.

Run from the repository root:

```bash
make test-integration-module MODULE=adapters/adapter-source-tika
```

The [module reference](../../../../../../../../README.md) describes dependencies
and the complete gate. Local tests do not qualify arbitrary shared-parser
concurrency, whole-service RSS, malicious archive/decompression limits, every
encoding or every installed format. Application scratch cleanup, bounded disk,
pre-commit policy and daemon admission are owned by their cross-module suites;
[processing](../../../../../../../../../../docs/dev/processing.md) and [native capacity](../../../../../../../../../../docs/dev/native-capacity.md)
explain those boundaries.
