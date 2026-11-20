# com.iocextractor.domain.refang

## Responsibility and boundary

Restore defanged text through caller-supplied ordered literal substitutions.
No regex replacement, configuration loading or IO lives here. This capability is
independent of the other domain packages.

## Types

| Source | Responsibility |
|---|---|
| [Refanger](Refanger.java) | Materialized and workspace-backed rewrite contracts |
| [ReplacementRefanger](ReplacementRefanger.java) | Default sequential literal-rule evaluator |
| [RefangRule](RefangRule.java) | Literal `from` / `to` substitution |
| [RefangOutcome](RefangOutcome.java) | Result text and immutable applied-rule facts |
| [RefangDecision](RefangDecision.java) | Original zero-based rule index and positive replacement count |
| [TextRewrite](TextRewrite.java) | Caller-owned text storage with one left-to-right replacement pass per rule |

## Ordering and outcomes

Every rule sees the output of the previous rule. Rule order therefore remains part
of the supplied policy; rules with no matching occurrences produce no decision. Each pass counts
non-overlapping occurrences and does not recursively rescan its own replacement.
Decisions retain declaration order and original rule indices. Empty text is an
explicit no-op for both default entry paths.

The string entry point returns transformed text and decisions. The `TextRewrite`
entry point mutates the supplied text representation and returns decisions; the
caller owns its storage and lifetime. A custom `Refanger` implementing only the
string method fails explicitly on the default streaming method.

Empty literals have Java string-replacement semantics on nonempty string input
(insertion at character boundaries). This is not validation of an operator rule
catalog: bootstrap owns configuration admission, and document-backed storage must
implement its admitted rewrite contract. The domain does not repair malformed
rules. See [refang tests](../../../../../../test/java/com/iocextractor/domain/refang/ReplacementRefangerTest.java)
for ordered passes, counts, empty input and streaming dispatch.
