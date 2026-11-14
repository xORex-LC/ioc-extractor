# com.iocextractor.domain.classify

## Responsibility and boundary

Evaluate declarative mask rules over derived indicator features. This capability
depends on `feature` and `model`; configuration resolution, artifact routing and
rendering remain outside. The evaluator contains no fixed four-variant code table.

## Types

| Source | Responsibility |
|---|---|
| [MatchPolicy](MatchPolicy.java) | Complete classification decision for one indicator |
| [RuleBasedMatchPolicy](RuleBasedMatchPolicy.java) | Extract features once per call and choose the first matching rule |
| [MatchRule](MatchRule.java) | Aligned predicate names/functions combined with AND, plus output codes |
| [FeaturePredicate](FeaturePredicate.java), [FeaturePredicates](FeaturePredicates.java) | Named feature tests and built-in registry |
| [ClassificationDecision](ClassificationDecision.java) | Features, matched-rule index/names and `MaskMatch` |

## Rules and decisions

Rules are evaluated in supplied order. Empty `when` matches unconditionally, so an
early catch-all shadows later rules. A rule must have as many predicate names as
functions. If none matches, the decision has index `-1`, no matched names and
`MaskMatch(null, null)`; this differs from a matched rule whose supplied codes happen
to be null. Decision predicate lists are copied.

The built-in names are `has-query`, `has-path`, `has-port`, `has-path-or-port`,
`is-ip`, `is-registrable`, `is-subdomain` and `is-onion`. No built-in fragment test
is registered. `HostClassifier` supplies host kind through feature extraction;
this package does not implement PSL lookup. Callers resolve configured names to
functions before constructing rules and own admission of code values.

`classify` computes one complete decision per invocation. It does not memoize
across calls or prohibit a caller from classifying file indicators; the processing
layer owns routing, reuse and any non-network bypass. The configured four-variant
policy and artifact use are explained in [processing](../../../../../../../../../docs/dev/processing.md).
See [classification tests](../../../../../../test/java/com/iocextractor/domain/classify/ClassificationContractsTest.java)
for predicate vocabulary, AND/catch-all, first-match and unmatched behavior.
