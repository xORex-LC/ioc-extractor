# Events core tests

Deterministic JUnit Jupiter/AssertJ checks for the JDK-only event contracts and
helpers. Test-owned events keep payload validation independent of IOC, Spring,
logging and transport. No external services or credentials are required. Read
the [module reference](../../../../../../../README.md) and
[production package](../../../../../../main/java/com/iocextractor/platform/events/README.md)
for dependency and behavior ownership.

## Test map

| Source | Observable contract |
|---|---|
| [ControlEventMetadataTest.java](ControlEventMetadataTest.java) | Six supplied fields, optional causation, null/blank text including Unicode whitespace, positive version, required timestamp, untrimmed text and producer-chosen future time/version |
| [ControlEventPublisherTest.java](ControlEventPublisherTest.java) | No-op/null handling, sequential recording, immutable snapshots, original/duplicate references, no metadata inspection, mutable payload sharing and no event loss with two concurrent writers |
| [NoopControlEventObserverTest.java](NoopControlEventObserverTest.java) | Valid publication/dispatch observations, required event/failure arguments, null/blank handler names, untrimmed nonblank names and no metadata inspection |

Three suites use Surefire naming, with no local Failsafe suites or resource
fixtures. Parameterized metadata/observer cases verify exception kinds at the
public boundary, rather than assuming all null inputs have one exception type.

The recorder concurrency case uses a timed barrier and bounded future waits,
with a containing `@Timeout`; its `finally` shuts down workers and asserts bounded
termination. It checks that all events survive, without asserting invocation-time
order for overlapping publications. Sequential and shallow snapshot guarantees
have separate checks. It does not make mutable payloads safe for concurrent use.

## Running and qualification limits

From the repository root:

```bash
make test-module MODULE=platform/platform-events
make test-one MODULE=platform/platform-events TEST=ControlEventPublisherTest
make verify
```

These tests qualify core metadata/helpers, not handler dispatch, all-handler
completion, durable recovery, retries or a wire protocol. Actual adapter behavior
is tested in [adapter-events-spring](../../../../../../../../../adapters/adapter-events-spring/README.md);
service recovery is described by [event coordination](../../../../../../../../../docs/dev/event-coordination.md).
Bootstrap's [ArchitectureTest](../../../../../../../../../bootstrap/ioc-app/src/test/java/com/iocextractor/ArchitectureTest.java)
checks the framework/business/broker boundary, event serialization prohibition
and separation of keyed execution.

The [standalone JDK consumer](../../../../../../../../../tools/fixtures/events-consumer/README.md) separately
compiles the exact EN/RU guide example against packaged JARs outside the checkout,
checks dependency absence and primary artifact hashes/origins, and retains
independent assertions. Publication tooling checks Java/XML parity and extraction.
Follow the [publication procedure](../../../../../../../../../docs/guides/library-publication.md#events-packaging-preparation)
for complete bundle/consumer steps. Local API tests or guide execution do not
refresh remote repository qualification or prove another deployed service.

Add regressions for changed validation, ownership or supported recording behavior;
keep fixtures synthetic and assertions independent of private storage details.
Use [TESTING.md](../../../../../../../../../docs/TESTING.md) for lifecycle and coverage rules. Update public
Javadoc, owning references and paired guides with a contract change; historical
publication evidence stays in immutable component records.
