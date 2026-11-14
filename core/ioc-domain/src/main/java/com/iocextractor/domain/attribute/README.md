# com.iocextractor.domain.attribute

## Responsibility and boundary

Attach section provenance from the nearest selected marker at or before each IOC.
Marker patterns compile through `extract.PatternEngine`; this capability depends
only on `extract` and `model`. Configuration, document storage, diagnostics and
CSV rendering remain outside.

## Types

| Source | Responsibility |
|---|---|
| [SourceAttributor](SourceAttributor.java) | Materialized attribution and lazy marker discovery |
| [MarkerSourceAttributor](MarkerSourceAttributor.java) | Default discovery, marker precedence and preceding-marker lookup |
| [MarkerCursor](MarkerCursor.java), [MergedMarkerCursor](MergedMarkerCursor.java) | Public marker scan and internal bounded-head merge |
| [SourceMarker](SourceMarker.java) | Nonnegative absolute UTF-16 position and normalized label |
| [AttributionDecision](AttributionDecision.java) | Raw occurrence plus optional selected marker; creates its `Indicator` |
| [AttributionOutcome](AttributionOutcome.java) | Immutable selected markers and per-occurrence decisions |

## Selection and attribution

Candidates come from the original text and, when NBSP occurs, a same-length view
that replaces NBSP with space. Candidate order is start position, decreasing match
length, then configured pattern order. Selection keeps the earliest non-overlapping
candidate; the longest candidate wins at the same start. Adjacent markers remain
eligible. Labels replace NBSP, collapse whitespace runs and trim surrounding spaces.
Offsets still refer to the same refanged UTF-16 text as raw occurrences.

A marker at exactly the IOC position applies inclusively. Before the first marker,
the materialized indicator has `SourceContext(null, null)`. Attribution does not
change the occurrence value/type or deduplicate it, and sets no subsection value.

The string path preserves input occurrence order, including unordered and equal
positions. Once markers have been selected, ordered input uses one advancing
lookup cursor; unordered input uses binary search. The lazy marker path retains
matcher heads instead of a document-wide marker list. The application pairs it
with source-ordered occurrences; the domain cursor itself only discovers markers.

Lazy scanning checks marker length before copying its label and checks interruption
while advancing. Failure does not undo already emitted markers. `value()` requires
a current marker after a successful `next()`; it fails before iteration or after
exhaustion. Batch-only implementations fail explicitly on the default lazy method.

[Invariant tests](../../../../../../test/java/com/iocextractor/domain/attribute/MarkerSourceAttributorInvariantTest.java)
cover NBSP/overlap precedence, inclusive boundaries, shuffled input, lazy limits and
cancellation. Cross-module stage ownership lives in [processing](../../../../../../../../../docs/dev/processing.md).
