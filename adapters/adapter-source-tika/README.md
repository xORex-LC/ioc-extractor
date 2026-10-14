# adapters/adapter-source-tika

## Purpose

Outbound document parsing behind the application `SourceReader` port.
Production writes decoded text to a caller-owned `Writer`; the finite `String`
API remains an explicit convenience and test oracle.

HTML uses `StreamingHtmlParser`: progressive jsoup HTML5 parsing, pruning completed
subtrees and Tika-compatible body text mapping. Repair regions remain intact until
complete. DOCX uses Tika's SAX extractor. PDF, DOC, XLSX and other installed Tika
formats retain their parser implementations and format contracts; their working
memory needs separate qualification.

## Structure

| File or directory | Responsibility |
|---|---|
| `pom.xml` | Parent-managed Tika and jsoup integration family |
| `src/main/java/com/iocextractor/adapter/out/source/` | Reader, admitted HTML parser, typed source diagnostic boundary |
| `src/test/java/com/iocextractor/adapter/out/source/` | Charset/format contracts, independent finite HTML/DOCX text oracle and parser-limit rejection |

## Ownership and limits

The reader owns its input stream and parser resources; application owns the
output writer and document workspace. HTML region characters/node count/depth
have explicit bounds. Oversized regions, nesting over 256 and embedded
`srcdoc`/data attributes fail preparation without truncation or a full-DOM
fallback. `ioc.processing.workspace.maximum-row-bytes` also controls the HTML
region character bound. IOC/marker size and aggregate text/disk bounds are
workspace responsibilities. See [ADR 0039](../../docs/ADR/0039-streamed-document-source-processing.md).

## Dependencies

Depends on `ioc-application`, diagnostics/observability platform contracts,
Tika, jsoup and SLF4J. Application/domain never import parser classes. Tika,
jsoup, POI and PDFBox versions belong to parent `dependencyManagement`; no new
integration module or public parser abstraction is introduced.
