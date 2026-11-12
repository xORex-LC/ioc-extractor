# com.iocextractor.adapter.out.regex

## Purpose and structure

Concrete engines and internal wrappers for the domain `PatternEngine` port.
Follow the [module dependency rules](../../../../../../../../README.md).

| File | Responsibility |
|---|---|
| [Re2jPatternEngine.java](Re2jPatternEngine.java) | Public RE2/J implementation with ID `re2j` |
| [JdkRegexPatternEngine.java](JdkRegexPatternEngine.java) | Public `java.util.regex` implementation with ID `jdk` |
| [CursorCompiledPattern.java](CursorCompiledPattern.java) | Package-private shared implementation: new cursor per request and eager `findAll` convenience |
| [MatcherCursor.java](MatcherCursor.java) | Package-private record delegating advance/start/end/full-match capture to one engine matcher |

`compile` invokes the selected engine's Pattern compiler directly and captures
that compiled pattern in a matcher factory. Each `matches` call creates a fresh
matcher/cursor. No adapter-level global cache, retry or fallback exists.
Configuration selection lives in bootstrap, not in these classes.

## Matching protocol and ownership

Call `next()` to find the next non-overlapping match in left-to-right order.
After a successful advance, `start()` and `end()` describe `[start, end)` in the
entire supplied UTF-16 sequence, and `value()` returns the full match (group zero).
Offsets are neither byte positions nor Unicode code-point indexes; a preceding
supplementary character occupies two UTF-16 positions. Capturing groups are not
exposed by the port. Do not read match fields before the first successful advance
or after exhaustion; matcher-state rules are delegated to the engine.

The input `CharSequence` is borrowed, not converted wholesale to a String by the
adapter. Keep it readable and stable until cursor use ends; any underlying
workspace/stream/resource remains caller-owned. The cursor is stateful and must
not be shared between threads. Reuse compiled patterns with separate cursors
and stable inputs; no mutable per-input matcher is stored in the compiled wrapper.

`value()` materializes the current match string. Bounded callers should inspect
`end - start` before requesting it. `findAll` eagerly traverses the cursor,
constructs every Span/string and returns a fresh mutable ArrayList in match order.
It has no result-count/length cap. Empty matches follow native engine behavior;
the adapter does not filter them or apply IOC acceptance/overlap rules.

These wrappers own no closeable resource and perform no input normalization,
offset rebasing or deduplication. No adapter timeout/interrupt check runs inside
`next`. In particular, a caller's checks between matches do not interrupt a
pathological JDK search already in progress.

## Syntax, failures and selection

Both implementations use ordinary default compilation without a separate flags
argument. Inline options remain engine syntax and must be compatible with the
project's common subset. RE2/J rejects unsupported look-around/back-references;
the JDK compiler can accept broader Java syntax, but project patterns must still
work with RE2/J. Common syntax alone does not establish equivalent behavior for
every arbitrary pattern/input, Unicode boundary or zero-width case.

Malformed expressions propagate engine-native unchecked failures, including
`com.google.re2j.PatternSyntaxException` or `java.util.regex.PatternSyntaxException`.
Null regex/input and invalid matcher state also follow underlying engine behavior.
The adapter does not turn errors into empty matches, wrap them as diagnostics
or automatically rerun them on another engine. RE2/J removes catastrophic
backtracking risk; this is not a whole-pipeline capacity or arbitrary-input
admission guarantee.

## Callers and verification

[RegexIndicatorExtractor](../../../../../../../../../../core/ioc-domain/src/main/java/com/iocextractor/domain/extract/RegexIndicatorExtractor.java)
owns IOC priority/overlap decisions. Its document overload uses lazy cursors,
checks length before copying a match, and records through `ExtractionClaims`;
its finite String overload uses `findAll`.
[MarkerSourceAttributor](../../../../../../../../../../core/ioc-domain/src/main/java/com/iocextractor/domain/attribute/MarkerSourceAttributor.java)
uses the same port for section headers. [MatchCursor](../../../../../../../../../../core/ioc-domain/src/main/java/com/iocextractor/domain/extract/MatchCursor.java)
and [Span](../../../../../../../../../../core/ioc-domain/src/main/java/com/iocextractor/domain/extract/Span.java)
keep offsets/results engine-neutral.

The [test package](../../../../../../../test/java/com/iocextractor/adapter/out/regex/README.md) maps the shared cursor/batch contract
and separate bootstrap pattern checks. [Processing internals](../../../../../../../../../../docs/dev/processing.md)
owns document workspace/limit behavior; precise operation guarantees remain in
Java Javadoc.
