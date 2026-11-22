# com.iocextractor.adapter.out.source

This outbound package implements document decoding through the application
[SourceReader](../../../../../../../../../../core/ioc-application/src/main/java/com/iocextractor/application/port/out/SourceReader.java)
port. [Module rules](../../../../../../../../README.md) own dependencies and
verification; exact method contracts remain with the Java sources.

## Source map

| Source | Responsibility |
|---|---|
| [TikaSourceReader.java](TikaSourceReader.java) | Public adapter, parser selection, per-read metadata/context, charset and typed failures |
| [StreamingHtmlParser.java](StreamingHtmlParser.java) | Package-private HTML5 parser, region admission/pruning, bounded reader and Tika-compatible body text events |

## Reader ownership and failures

The reader opens/closes the input stream and creates metadata, parse context and
body handler per read. It borrows the output writer; the caller closes it. The
writer-backed handler removes Tika's default output truncation without imposing
a whole-document bound here. The `StringWriter` convenience path retains the
complete text in heap. Streaming callers must own overall text/disk quotas and
remove partial output after failure. This adapter performs no canonical I/O.

A null forced charset selects detection; an explicit charset installs a constant
`EncodingDetector` for text/HTML, overriding document hints. Binary formats use
their own internal encoding. HTML falls back to US-ASCII if detection returns
null. A wrong forced charset can produce wrong text; the knob does not guarantee
detection of encoding mistakes. Output is Unicode; the workspace owns absolute
UTF-16 offsets and later refanging.

A directly thrown `UnsupportedFormatException` becomes
`SOURCE.UNSUPPORTED_FORMAT`, with the extension (or `unknown`) as diagnostic
context. The extension is a context hint, not the detected MIME type. Other caught
exceptions become `SOURCE.READ_FAILED`, with fixed reasons for path validation,
stream opening and parsing or text streaming. The adapter preserves its own HTML
limit and admission reasons. The original exception tree and
message are dropped at this boundary. JVM `Error` propagates. `SOURCE_READ`
records successful decoding and input closure before canonical processing.

Per-read objects do not imply that the shared third-party parser graph has been
qualified under arbitrary concurrent use. The local suites exercise synchronous
reads; resource admission and document-worker ownership belong to the application.

## Progressive HTML contract

`StreamingHtmlParser` overrides HTML, XHTML, WAP XHTML and x-asp media types in
Tika's auto-detect registry. jsoup `StreamParser` supplies completed elements.
Text order is preserved by emitting preceding siblings and balanced ancestor
pairs before removing completed subtrees. Regions involving tables or formatting
repair (`table`, `b`, `i`, `a`, `strong`, `em`, `font`, `template`) are retained
until complete because repair can move text before earlier siblings. A repair
that changes an already emitted ancestor fails explicitly.

The parser reads Tika's bundled `self-closeable-tags.txt` and uses its default
HTML mapper plus `XHTMLContentHandler` for safe body text, whitespace and image-alt
behavior. That resource and output mapping are compatibility seams when upgrading
Tika/jsoup; this is text extraction, not a general HTML sanitizer.

Admission combines several checks:

- `RegionReader` limits decoded characters read since the last completed-region
  progress point; it resets only when emission advances. Exact EOF at the limit
  is allowed; an additional character fails.
- Retained text/data/attribute characters must fit the region limit. The node cap
  is `limit / 4 + 1`; the ancestor check rejects chains longer than 256, including
  parser-created ancestors. jsoup's stack bound is set to 257 to expose rejection.
- Any `srcdoc` attribute or case-insensitive, leading-space-trimmed `src=data:`
  value fails during element emission, including discarded elements. This is not
  a universal embedded-object policy for all document formats.

Interruption is checked on HTML reader calls and raises `IOException` without
clearing the interrupt flag. It is not a timer or a format-independent cancellation
contract. Parsing closes owned reader/parser resources even on rejection; the
caller must discard text already emitted. Parser-budget gaps remain
[SRC-2](../../../../../../../../../../docs/KNOWN-ISSUES.md#src-2). The
[test map](../../../../../../../test/java/com/iocextractor/adapter/out/source/README.md)
describes the finite exact-output oracle and tested limits.
