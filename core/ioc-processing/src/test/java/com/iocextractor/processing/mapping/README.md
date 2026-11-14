# Mapping tests

## Responsibility and suites

Four suites exercise the shared evaluator and supplied components independently
of CSV encoding, bootstrap configuration admission or route execution.

| Source | Contract covered |
|---|---|
| [ConfigurableRowMapperTest](ConfigurableRowMapperTest.java) | Header order, null/deferred cells, gates, column views, conditions, ordered/argument-preserving transforms, lazy unresolved failures, source-label presentation, located typed rejection and unexpected-defect propagation |
| [AddressValueProviderTest](AddressValueProviderTest.java) | Bare IPv4 versus URL/domain/port/path routing into complementary address columns |
| [LowerHostTransformTest](LowerHostTransformTest.java) | Scheme/authority casing with case-sensitive path/query/fragment suffixes |
| [ArtifactFilterTest](ArtifactFilterTest.java) | Include conjunction and exclude veto |

The mapper suite counts feature extraction to show that multiple columns consume
one materialized decision. Its synthetic registries include an `id` provider that
returns null; this is not ID-allocation or write-boundary evidence. Address tests
use real domain feature parsing with a simple host-kind stub, not the PSL adapter.

See the [production mapping reference](../../../../../../main/java/com/iocextractor/processing/mapping/README.md)
for extension/error contracts and the [test root](../README.md)
for commands and wider evidence boundaries.
