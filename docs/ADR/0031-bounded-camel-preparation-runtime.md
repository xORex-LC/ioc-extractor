# ADR 0031: Bounded Camel preparation runtime

Status: Accepted for staged implementation, 2026-09-27. R0 admission only;
production integration and release qualification remain outstanding.

## Context

Per-artifact and per-field processing requires original and derived values,
ordered operations and explicit routing. Document extraction and processed import
must share those operations while retaining different admission and persistence
contracts. Current deduplication/classification precedes artifact preparation,
and processed import currently receives rows whose keys have already been built.
Neither seam can be fixed by wrapping the existing mapper in a routing framework.

The existing platform pipeline owns stage observations and failure checkpoints.
Canonical identity, registered observation precedence, lifecycle and receipt-based
recovery already have application/storage owners. Routing must not duplicate them.

## Decision

Use embedded Apache Camel for the bounded inner preparation execution, behind
framework-free application ports. Initially qualify Camel 4.22.1 with Java 21
and the existing Boot 4.0.8 management. Use explicit lifecycle wiring rather than
a Camel Boot starter. A two-test isolated admission probe established synchronous
direct execution, startup, propagated operation exceptions without redelivery,
rejection of an invalid route and shutdown by the Spring bean owner. This is not
evidence of complete application or starter compatibility.

Create `adapter-processing-camel` in the subsequent module-admission slice.
Separate neutral `contract`, `compile`, `runtime` packages from application-facing
`bridge` code; neutral packages must not depend on IOC/domain/application types.
Enforce this with architecture tests. The adapter as a whole implements
application ports and is not advertised as a generic published library.
Do not create a second `platform-routing` interpreter. If a separately consumable
neutral runtime becomes necessary, extract the same implementation and its small
contracts rather than creating another executor.

Pure IOC operations may move into `ioc-processing` only with dependency closure:
it may depend on domain/platform contracts but never application, CSV or Camel.
Relocate minimal view/mapping values first; current application payloads cannot
be pulled inward by copying their package dependencies.

The operator configures a bounded catalog of operations/predicates and ordered
AND/OR/NOT conditions. Bootstrap owns configuration binding and semantic admission;
the adapter compiles admitted descriptors into execution nodes. No unrestricted
Camel DSL, scripts, reflection or input-derived endpoint addresses are exposed.

Conditions produce MATCH, NO_MATCH or BLOCKED. FIRST stops at the first MATCH or
BLOCKED; EXCLUSIVE dispatches only after proving exactly one match. ALL may prepare
independent branches despite expected failures. The caller's existing failure
policy controls durable admission. A selected branch failure never reselects a
later FIRST branch. Defaults apply only to conclusive no-match.

Recovery creates a separately named view and handles only registered expected
failure reasons. Final diagnostics are resolved before publication: a recovered
failure becomes WARN only when every demanded consumer recovers; an unrecovered
consumer retains ERROR. Unexpected exceptions propagate. No operation publishes
diagnostics or logs payloads directly; existing observers/sinks retain ownership.

Document and import entry ports remain distinct. The former receives attributed
occurrences before destructive reduction, the latter admitted structured cells
before final output keys. Canonical finalization remains outside Camel, as do
source authority, imported slot validation, ledgers and commits. Preserve document
per-artifact commit semantics and import cross-artifact transaction semantics.

Only new observations use newly activated cleanup policies. There is no backfill;
old rows expire only when the configured lifecycle is active. Pinned deliveries
must not silently switch policy on recovery.

## Consequences and alternatives

Camel owns operation/branch execution; a narrow selector may provide exact
preselection semantics, but must not duplicate condition evaluation. The outer
platform pipeline remains in place. Explicit Spring lifecycle integration adds
ownership code and tests but avoids broad starter auto-configuration.

A JDK selector plus existing execution was the smaller alternative, but would
leave operation composition to project-owned orchestration. It remains a revised
decision option if later qualification rejects Camel, not a parallel engine.
Migrating intake, sync, scheduling or durable recovery to Camel is out of scope.

Every new production module must enter the existing Maven, architectural,
test-lifecycle, JaCoCo, SpotBugs, CPD and PMD gates in the same change. No lowered
floor, hidden source exclusion or report-scope omission is permitted. Full
application compatibility, concurrent lifecycle behavior, resource budgets and
recovery parity must pass before production activation.

## References

- [Architecture](../ARCHITECTURE.md)
- [Build quality](../dev/build-quality.md)
- [Testing policy](../TESTING.md)
- [Apache Camel 4.22.1 release](https://camel.apache.org/releases/release-4.22.1/)
- [ADR 0030: observation order](0030-registered-observation-order-for-artifact-fields.md)

## Implementation note, 2026-09-29

R1–R5 admitted the bounded compiler/runtime and activated explicit document and
processed-import selections. P6 qualifies a selected plan against canonical
outputs and publishes the operator activation procedure. Unselected processed
contracts still require the compatible CSV preparer; removing it would change
the accepted legacy behavior, so it is not retired by this decision.

## Superseded scope, 2026-10-04

[ADR 0034](0034-required-router-processing-plans.md) supersedes optional document
selection and retained compatible processed-import dispatch. Router now requires
explicit bindings; original-value defaults preserve source observation accounting.
The bounded Camel ownership and canonical authority decisions remain unchanged.
