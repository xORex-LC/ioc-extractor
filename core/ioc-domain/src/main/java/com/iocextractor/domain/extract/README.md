# com.iocextractor.domain.extract

## Responsibility and boundary

Detect typed occurrences in refanged text through an engine-neutral regex port.
Only `model` is an allowed domain dependency. Patterns and their priority are
supplied by the caller; this package does not load configuration or validate a
complete IOC independently of the configured patterns.

## Types

| Source | Responsibility |
|---|---|
| [PatternEngine](PatternEngine.java) | Engine identity, reusable compilation, materialized matches and lazy matcher |
| [MatchCursor](MatchCursor.java) | Current match offsets and deferred match-value copying |
| [IndicatorExtractor](IndicatorExtractor.java) | Materialized outcome and streaming extraction entry points |
| [RegexIndicatorExtractor](RegexIndicatorExtractor.java) | Compile once, scan by type priority and resolve overlaps |
| [ExtractionClaims](ExtractionClaims.java) | Invocation-owned overlap state and ordered decision recording |
| [Span](Span.java), [RawIndicator](RawIndicator.java) | Half-open match range / accepted value, type and start position |
| [ExtractionDecision](ExtractionDecision.java), [ExtractionDecisionStatus](ExtractionDecisionStatus.java) | `ACCEPTED` or `DROPPED_OVERLAP` with type, pattern and span |
| [ExtractionOutcome](ExtractionOutcome.java) | Immutable accepted occurrences and all raw-match decisions |

## Priority, offsets and storage

The supplied map's iteration order defines type priority. With URL patterns ahead
of domain patterns, a URL claims its host span before a bare-domain match can win.
Each pattern reports non-overlapping matches left to right. All positions are
absolute UTF-16 indices into the supplied refanged text; span ends are exclusive.

The string path uses document-sized overlap state, materializes all decisions and
stable-sorts accepted occurrences by position. Decisions remain in pattern-priority
order. Null or empty string input produces an empty outcome. Deduplication by IOC
value/type happens later; overlap resolution only concerns character ranges.

The streaming path scans a borrowed `CharSequence` with lazy cursors, checks each
match length before materializing its value, and records every decision through
`ExtractionClaims`. Its owner must make accepted claims visible to subsequent
overlap queries, preserve decision order and provide accepted occurrences in source
order when needed. State is invocation-owned; reusing stale claims changes results.
The backing text must remain valid throughout matching.

Oversize matches fail with `IllegalArgumentException`; an observed interrupt fails
with `IllegalStateException`. Earlier recorded decisions can remain after failure;
they are scratch facts, not canonical writes. A batch-only extractor or compiled
engine inherits an explicitly unsupported streaming method.

Configured patterns must remain RE2-compatible (no look-around/back-references)
for both [engine adapters](../../../../../../../../../adapters/adapter-regex-re2j/README.md). Domain tests use
[a literal fake](../../../../../../test/java/com/iocextractor/domain/support/README.md),
so they establish overlap/order contracts rather than production regex semantics.
