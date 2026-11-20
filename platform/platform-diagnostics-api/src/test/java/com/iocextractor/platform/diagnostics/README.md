# Diagnostics API tests

Deterministic library behavior checks using consumer-owned codes and explicit
clocks. These sources depend on the library, JUnit Jupiter and AssertJ; they
require no IOC catalog, framework, logger, database, network or credentials.
The [module reference](../../../../../../../README.md) owns dependency
and publication rules.

## Test map

| Source | Observable contract |
|---|---|
| [DiagnosticTest.java](DiagnosticTest.java) | Consumer code equality, effective severity, timestamps, shallow snapshots, builder reuse and invalid/partially accepted context |
| [DiagnosticsLibraryExampleTest.java](DiagnosticsLibraryExampleTest.java) | Independent behavior witnesses for results/policies, one-sample stage/run counts and resilient delivery |
| [TestCodes.java](TestCodes.java) | Shared test-owned metadata across ELEMENT, RUN and OPERATION; no product catalog dependency |
| [result/ResultTest.java](result/ResultTest.java) | Copied ordered diagnostics, nullable value, map success/null/failure and severity helpers |
| [result/NotificationTest.java](result/NotificationTest.java) | Mutable accumulation, snapshots/results, partial bulk failure and caller-owned policy enforcement |
| [result/FailurePolicyTest.java](result/FailurePolicyTest.java) | Empty/warning/error/fatal outcomes, list-order selection, nonmutation and invalid inputs |
| [result/BoundedNotificationTest.java](result/BoundedNotificationTest.java) | Stage/run budgets, late rejecting signals, OPERATION exemption, replacement order, display markers, batch validation, exact merge and partial callback/overflow failures |
| [result/DiagnosticSummaryTest.java](result/DiagnosticSummaryTest.java) | Exact counts/deltas, explicit zeros, copied maps, invalid counts and checked overflow |
| [sink/CollectingDiagnosticSinkTest.java](sink/CollectingDiagnosticSinkTest.java) | Emission order, snapshots, no-op behavior and null rejection |
| [sink/ResilientDiagnosticSinkTest.java](sink/ResilientDiagnosticSinkTest.java) | Original failure/diagnostic delivery, successful path, contained fallback failure, null rejection and propagated Errors |

Nine suites use Surefire naming. There are no local Failsafe suites or external
service requirements. The bounded suite compares direct accumulation with
same-budget stage merging using a fixed-seed stream, and separately checks the
loss of samples with a smaller stage budget. It also distinguishes retained
detail, exact counts and delivery calls; a retained limit is not a delivery cap.

## Running and extending

From the repository root:

```bash
make test-module MODULE=platform/platform-diagnostics-api
make test-one MODULE=platform/platform-diagnostics-api TEST=BoundedNotificationTest
make verify
```

Add regressions for changed observable contracts at the relevant level: value
ownership, null/budget/count boundaries, stopping signals, merge prefix or callback
behavior. Keep assertion fixtures independent of IOC metadata and do not infer
thread safety from sequential tests. The mutable collectors intentionally belong
to one task. Local API tests do not prove arbitrary custom-policy correctness,
wire compatibility, another production integration or remote publication.

The [standalone consumer](../../../../../../../../../tools/fixtures/diagnostics-consumer/README.md) separately
extracts and compiles the three actual EN/RU guide examples against a resolved
packaged artifact outside the checkout; `DiagnosticsLibraryExampleTest` supplies
behavior witnesses rather than literal guide-source compilation. Publication
contracts check extraction and language parity. Use the
[publication procedure](../../../../../../../../../docs/guides/library-publication.md#local-packaging-qualification)
for this qualification; running the unprepared fixture directly is unsupported.

Reactor integration checks live outside this module: the
[architecture test](../../../../../../../../../bootstrap/ioc-app/src/test/java/com/iocextractor/ArchitectureTest.java)
enforces JDK-only API dependencies; [ETL tests](../../../../../../../../platform-etl/src/test/java/com/iocextractor/platform/etl)
check runner counts, policies and delivery integration. Read
[TESTING.md](../../../../../../../../../docs/TESTING.md) for test lifecycle and coverage policy. Update public
Javadoc, owning package/module references and paired consumer guides when behavior
changes; historical release qualification remains in its permanent record.
