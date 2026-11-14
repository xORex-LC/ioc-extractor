# Processing tests

## Responsibility and structure

Eight Surefire suites exercise the pure preparation boundary with synthetic
values, extractors, policies and registries. There are no test helper packages,
resource fixtures or local Failsafe suites. This module uses inherited JUnit/AssertJ
and local ArchUnit rather than depending on the application TCK.

| Source / directory | Contract covered |
|---|---|
| [ProcessingBoundaryTest](ProcessingBoundaryTest.java) | Outward application/adapter/bootstrap, framework and JDBC dependency guard |
| [classification/IndicatorClassifierTest](classification/IndicatorClassifierTest.java) | Network delegation and neutral file-policy bypass |
| [parse/ExactIndicatorParserTest](parse/ExactIndicatorParserTest.java) | Whole-cell completeness, typed failures and exclusive result states |
| [session/IndicatorProcessingSessionTest](session/IndicatorProcessingSessionTest.java) | Source/type/policy identity, host-source reconstruction, budget/LRU, failures, close and independent concurrent sessions |
| [mapping](mapping/README.md) | Field/view evaluation, transforms, value rejection and address/filter behavior |

## Running and interpreting results

Run from the repository root:

```bash
make test-module MODULE=core/ioc-processing
make test-one MODULE=core/ioc-processing TEST=ConfigurableRowMapperTest
```

Extractor/policy doubles isolate contracts, not real regex/PSL behavior. Independent
concurrent sessions do not qualify sharing one session or a mutable custom policy.
Local suites do not qualify actual CSV bytes, route execution, managed-import
recovery or document workspace memory. Those integrations live in adapters and
bootstrap; `make verify` is the full deterministic reactor gate.
Apply [test policy](../../../../../../../../docs/TESTING.md) when extending this tree; the
[module README](../../../../../../README.md) owns production dependencies.
