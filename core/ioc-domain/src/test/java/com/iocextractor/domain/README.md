# Domain tests

## Responsibility and structure

Surefire suites exercise pure domain contracts with synthetic data. They use direct
JUnit annotations/tags and inherited AssertJ support; this inward module does not
depend on the application TCK. There are no local Failsafe suites or resource fixtures.

| Source / directory | Contract covered |
|---|---|
| [DomainBoundaryTest](DomainBoundaryTest.java) | ArchUnit capability DAG and allowed inward package edges |
| [model/DomainModelInvariantTest](model/DomainModelInvariantTest.java) | IOC vocabulary, value/type dedup identity and source-label replacement |
| [refang/ReplacementRefangerTest](refang/ReplacementRefangerTest.java) | Literal substitutions, order/counts, empty input/literal and workspace dispatch |
| [refang/RefangDecisionTest](refang/RefangDecisionTest.java) | Applied-decision index/count validity |
| [extract/RegexIndicatorExtractorBoundaryTest](extract/RegexIndicatorExtractorBoundaryTest.java) | Type priority versus source order, overlap decisions, null/empty input, lazy equivalence and match limit |
| [attribute/MarkerSourceAttributorInvariantTest](attribute/MarkerSourceAttributorInvariantTest.java) | Marker precedence/NBSP, inclusive source boundary, shuffled input versus seeded independent oracle, lazy limits and interrupt handling |
| [feature](feature/README.md) | Normalization, address parsing/derivation, feature flags and bare-IP routing |
| [support](support/README.md) | Literal pattern-engine double used by extraction and attribution suites |

There are eleven suite classes and one support implementation. Parameterized cases
and ArchUnit rules contribute executed checks beyond the source-class count.

## Running and interpreting results

Run from the repository root:

```bash
make test-module MODULE=core/ioc-domain
make test-one MODULE=core/ioc-domain TEST=RegexIndicatorExtractorBoundaryTest
```

The literal double isolates ordering and span logic. Fixed host classifiers isolate
parsing and routing from PSL data. These suites do not qualify RE2/J syntax, Guava
PSL behavior, source-parser encoding, disk workspace bounds or full-service capacity.
Those integrations are owned downstream; the complete deterministic gate is
`make verify`. The module POM's PIT pilot is diagnostic rather than regular lifecycle
acceptance. Apply [test policy](../../../../../../../../docs/TESTING.md) when extending this tree.
