# ADR 0039 — Workspace-owned streamed document source processing

Status: Accepted, 2026-10-08.

## Context

The sealed preparation workspace from ADR 0036 bounded Router candidates and
winners but retained complete source/refanged strings, extraction decisions and
attributed occurrences before routing. Tika's HTML parser additionally retained
a complete DOM. Million-occurrence HTML exhausted the qualified JVM heap before
preparation. A fatal worker error could leave the oldest durable admission hidden
behind an incomplete in-process job until restart.

## Decision

The admitted document workspace owns decoded and refanged UTF-16 files. A fixed
two-page cache exposes the complete text as a `CharSequence`. Regex anchors and
matches therefore see one document, including page-crossing tokens; offsets
remain absolute UTF-16 positions in the fully refanged text. Each literal refang
rule makes an ordered non-overlapping disk pass without rematching its replacement.
There is no materializing fallback in the production pipeline.

Lazy match cursors run IOC types in the configured global priority order.
Indexed private accepted spans resolve overlaps without a document-sized bitmap.
The scratch store preserves decision encounter order and reads accepted
occurrences in position/stable encounter order. Marker cursors merge original
and NBSP-normalized views by position, longest match, configured pattern order
and original-view precedence. Attribution and Router consume closeable cursors;
canonical identity, observation selection, diagnostic participation and receipts
keep their existing contracts.

The Tika adapter uses progressive jsoup parsing with completed-subtree pruning
for HTML and SAX extraction for DOCX. Regions in which HTML repair can move text
are retained until complete. Nesting deeper than 256, excessive region text,
attributes or node count, and embedded `srcdoc`/data attributes reject preparation
before canonical writes. These are explicit input admission restrictions.
Other Tika formats remain supported by their existing parsers; this decision
does not claim that every third-party parser has bounded working memory.

`maximum-row-bytes` also bounds an HTML parser region in characters;
`maximum-field-bytes` bounds materialized IOC and marker values. The text and
source-decision store each use at most one quarter of the per-workspace disk
limit, with the combined factory disk guard still authoritative. Text length
must fit the Java regex UTF-16 offset range. Native caches are split between
source scratch and the reducer. Admission reserves
`cache-kib * 1024 + maximum-row-bytes * 64 + 65536` bytes per invocation,
including conservative parser/codec space. This reservation is not a universal
RSS formula: JVM and parser fixed overhead require process measurements. The
global configured memory and disk budgets are unchanged.

Ordered dispatch limits its active window to the smaller of the configured
window and admitted workspace capacity. Ready later documents cannot exhaust
leases while an older dispatched preparation waits to acquire one. This also
permits a one-lease deployment without increasing its memory budget.

Source scratch is disposable and is removed before sealing, after failed
preparation and on close. Replaying a sealed workspace rebuilds source scratch;
it never becomes canonical or recovery authority. Parser semantics and input
limits participate in the processing policy fingerprint. Canonical receipts and
the startup recovery barrier remain authoritative.

Fatal worker errors stop intake and promotion scheduling visibly, release ready
owned preparations and leave durable admissions pending for restart recovery.
An already executing promotion owns its transaction boundary and cleanup. The
failed dispatcher object cannot restart itself. Ordinary recoverable failures
retain their retry/rejection policy.

## Consequences

Source processing exchanges document-sized heap retention for bounded buffers
and private disk passes. Finite batch APIs remain useful for independent test
oracles and explicit callers; production uses the streamed contracts. Exact
text/offset/priority/attribution comparisons, actual DOCX fixtures, quota failures
and fatal-worker recovery tests qualify this boundary. Complete-service memory
and latency measurements remain separate from mechanism tests. Large canonical
transactions and private reducer capacity are independent follow-on decisions.

Parser references: [jsoup StreamParser](https://jsoup.org/apidocs/org/jsoup/parser/StreamParser)
and [Tika OOXML extractor selection](https://github.com/apache/tika/blob/3.3.2/tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-microsoft-module/src/main/java/org/apache/tika/parser/microsoft/ooxml/OOXMLExtractorFactory.java).
