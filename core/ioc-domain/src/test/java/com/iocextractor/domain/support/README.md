# Domain test support

## Responsibility and type

[LiteralPatternEngine](LiteralPatternEngine.java) is a deterministic test double for
`PatternEngine`. It treats each compiled string as a literal token, reports
left-to-right non-overlapping spans and exposes both materialized and lazy matching.
Compilation rejects null or empty tokens; its engine identity is `literal-test`.

The lazy cursor reads the supplied `CharSequence` by character without copying the
whole input. The materialized helper converts input to a string and collects spans.
These paths let extraction/attribution tests compare engine-neutral priority,
offsets, NBSP handling and overlap decisions with small fixtures.

This helper implements no regex syntax or production resource guarantee. Do not use
it as evidence for RE2 compatibility, regex boundaries or large-document capacity.
It remains test-only and carries no adapter dependency. See the
[test root](../README.md) for callers
and verification commands.
