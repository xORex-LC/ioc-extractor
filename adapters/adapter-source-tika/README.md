# adapter-source-tika

## Purpose and boundary

Internal `com.iocextractor:ioc-adapter-source-tika` implements the application
[SourceReader](../../core/ioc-application/src/main/java/com/iocextractor/application/port/out/SourceReader.java)
port. It converts documents to Unicode text; refanging, IOC extraction,
attribution, scratch storage and canonical commits belong to the application.
Bootstrap constructs it explicitly; this module contains no Spring configuration.

## Structure and dependencies

| Location | Responsibility |
|---|---|
| [pom.xml](pom.xml) | Parent-managed integration dependencies and test-support dependency |
| [Source package](src/main/java/com/iocextractor/adapter/out/source/README.md) | Public reader, internal progressive HTML parser, decoding, ownership and failure boundary |
| [Test package](src/test/java/com/iocextractor/adapter/out/source/README.md) | Synthetic formats, charset/diagnostic contracts and complete-text streaming comparisons |

Production dependencies point inward to application, shared diagnostic API,
IOC diagnostics and IOC observability. The external integration family is Tika
core/standard parsers, jsoup and SLF4J API. Standard parsers bring format-specific
implementations such as POI/PDFBox and their transitive closure; this is broader
than HTML/DOCX alone. Versions belong to root dependency management. The TCK's
composed test annotations are test-only. This is an internal adapter JAR, outside
the reusable-library publication catalog.

## Entry points and supported behavior

`TikaSourceReader.readText(Path, Writer)` feeds decoded text into a caller-owned
writer without Tika's default 100k-character output cutoff. The `String` overload
explicitly materializes the entire result and remains useful for finite callers
and test oracles. The production document path uses
[ReadSourceStreamStage](../../core/ioc-application/src/main/java/com/iocextractor/application/pipeline/stage/ReadSourceStreamStage.java).

[AppConfig](../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/AppConfig.java)
binds `ioc.source.charset` and supplies
`ioc.processing.workspace.maximum-row-bytes` as the HTML region character limit.
Constructors without an explicit region limit use 262144 characters. This setting
has different units at the parser boundary from its byte-oriented workspace name;
it is not a whole-document character quota.

Auto-detection selects installed parsers using content and resource-name metadata.
HTML replaces Tika's normal parser with progressive jsoup parsing and completed
subtree pruning; DOCX selects Tika's SAX extractor. PDF, XLSX, legacy DOC and other
installed formats retain their parser behavior. Minimal PDF/DOCX/XLSX fixtures
prove extraction, not equal memory/cancellation guarantees across those formats.
Daemon inbox extension admission is separately owned by ingestion.

The reader reports `SOURCE.UNSUPPORTED_FORMAT` for a directly reported Tika
`UnsupportedFormatException` and `SOURCE.READ_FAILED` for other caught read or
parse exceptions. The `DiagnosticException` carries the path and a controlled
reason or extension hint. The adapter drops the original exception and its
message. The caller owns rollback, rejection, retries and disposal of partial
text already written to its writer.

## Resource and security limits

HTML admission rejects excessive region read/retained characters, node counts,
nesting and embedded `srcdoc` or `src=data:` input. Repair-sensitive regions stay
intact until complete. Rejection does not truncate to a successful result or fall
back to a full DOM. The application owns disposal of partial scratch and the
pre-commit checkpoint. These mechanisms do not establish a uniform decompression
or wall-clock limit for every Tika format: see
[SRC-2](../../docs/KNOWN-ISSUES.md#src-2). The dated dependency advisory intake remains
[SRC-3](../../docs/KNOWN-ISSUES.md#src-3); a green parser test is not fresh security
qualification.

## Verification and related docs

From the repository root:

```bash
make test-integration-module MODULE=adapters/adapter-source-tika
make verify
```

All four local suites are Failsafe integration tests; `make test-module` alone
will not execute them. See the test-package map for the distinction between
complete-text equivalence, synthetic format witnesses and operational capacity.
[Processing](../../docs/dev/processing.md), [native capacity](../../docs/dev/native-capacity.md)
and [ADR 0039](../../docs/ADR/0039-streamed-document-source-processing.md) own the
cross-module flow, measurement scope and historical rationale.
