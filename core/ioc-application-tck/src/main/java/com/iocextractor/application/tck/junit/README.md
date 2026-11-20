# com.iocextractor.application.tck.junit

## Responsibility and sources

Shared composed JUnit annotations for adapter/bootstrap test suites.
[package-info.java](package-info.java) records this metadata boundary. All annotations
have runtime retention and are inherited by subclasses.

| Annotation | Tags / role |
|---|---|
| [ContractTest](ContractTest.java) | `contract`; reusable behavior/format contract |
| [IntegrationTest](IntegrationTest.java) | `integration`; real integration boundary |
| [EndToEndTest](EndToEndTest.java) | `integration` + `e2e`; includes `@IntegrationTest` |
| [ExternalTest](ExternalTest.java) | `integration` + `external`; includes `@IntegrationTest` |
| [SlowTest](SlowTest.java) | `slow`; cost marker without lifecycle reassignment |

`ContractTest` and `IntegrationTest` target types and annotation types, allowing
composition. The other three target suite types. These annotations do not target
individual methods or supply assertions, fixtures, timeouts, target provisioning
or property gates.

## Discovery and policy

Abstract TCK bases carry `@ContractTest`; concrete adapter integration subclasses
inherit it and add `@IntegrationTest`. Test names select Maven ownership:
`*IT`/`IT*`/`*ITCase` use Failsafe; the accepted test-name patterns use Surefire.
Adding an annotation does not move a wrongly named class into another lifecycle.

`@ExternalTest` requires a separate explicit provisioning-property gate according
to [TESTING](../../../../../../../../../../docs/TESTING.md). An offline skip supplies no live evidence.
`@SlowTest` alone neither skips a suite nor changes its owner. Framework-free
inward modules use direct JUnit tags when depending on the TCK would point outward.
The [TCK module README](../../../../../../../../README.md) owns dependency and
consumer setup; the test policy owns the complete accepted taxonomy.
