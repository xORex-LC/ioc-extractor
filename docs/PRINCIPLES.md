# Engineering principles

[Русская версия](ru/PRINCIPLES.md)

These principles guide design and review across the project. They state how to
choose responsibilities and preserve contracts. [ARCHITECTURE.md](ARCHITECTURE.md)
describes the running system; [MODULARIZATION.md](MODULARIZATION.md) maps its
modules; [BOUNDARIES.md](BOUNDARIES.md) identifies executable enforcement.

## Responsibility before abstraction

Give each component one coherent reason to change. Separate IOC semantics,
workflow orchestration, persistence, transport and runtime composition.

Introduce a port when a use case crosses a technology, side-effect or replaceable
implementation boundary. Pure collaborators within one layer may use each other
directly. An interface for every class and a new Maven module for every package
are not requirements. Prefer the smallest design that meets a demonstrated need.
Keep ports narrow and implementations substitutable: validation, failure and
resource-ownership guarantees are part of the contract.

Dependencies point inward. Domain and processing remain framework-free;
application owns use cases and inward-facing ports. Adapters implement technology
boundaries; bootstrap composes them. A platform directory does not automatically
make a component generic or suitable for publication.

## Declarative policy and explicit execution

Keep operator-specific rules in validated configuration: refang replacements,
patterns, attribution markers, classification rules, named processing plans and
artifact mapping. Code implements the supported operations and their invariants.
Configuration selects those operations; it is not an arbitrary scripting or
workflow engine.

Prefer an existing extension point when it fits. A new capability can still need a
focused change to its owning component. Open/Closed does not prohibit correcting a
defect or extending a public contract. Keep processing order, transaction boundaries
and recovery ownership explicit.

## Reuse follows ownership

Remove duplicated knowledge when it has one owner and one reason to change.
Similar code with different lifecycle, failure or security contracts does not by
itself justify a shared abstraction.

Before adding shared mechanics, inspect the
[library catalog](guides/libraries/README.md), public contracts, implementation,
tests and callers. Use a suitable API; fix or extend its library when the gap is a
coherent generic responsibility. Keep IOC catalogs, schemas, business rules and
durable recovery in the service. The [development guide](guides/libraries/development.md)
and [ADR 0048](ADR/0048-independent-library-versions-and-reuse.md) define this
workflow and the distinction between accepted version policy and current tooling.

## Durable truth and explicit identity

SQLite canonical state is business truth. Mutable CSV files and immutable export
slices are derived views. Coordination ledgers and commit receipts define what
can be resumed after a failure; filesystem notifications and control events
accelerate discovery.

Keep identities distinct: a delivered occurrence, its content hash, canonical row,
lifecycle, ordered observation and reusable export slot serve different contracts.
A public slot is meaningful in its profile/artifact context. Equal bytes do not
prove that two business occurrences are the same.

Evaluate failure policy before canonical writes. State transaction scope explicitly
and recover committed work forward from durable evidence. A projection or delivery
failure does not undo a successful canonical commit. Upgrade and rollback must
preserve a consistent application/configuration/database state.

## Bounded work and visible failures

Own resources explicitly and bound queues, workers, retained details and workspace
admission. Separate preparation from durable promotion where the workflow requires
it. An admission estimate is not a whole-process memory guarantee; capacity claims
need a named workload and measured evidence.

Diagnostics are structured outcomes with stable service-owned codes. Preserve the
primary failure and exact severity counts even when retained detail is bounded.
Logging, health and events support observation without becoming commit authority.
Secrets and untrusted IOC content require explicit disclosure boundaries.

## Contracts and evidence change together

Document public APIs and port guarantees with meaningful English Javadoc.
Update affected maps, capability docs, module references and guides with the change.
Keep generated references tied to their generators. [CONVENTIONS.md](CONVENTIONS.md)
defines documentation responsibilities.

Preserve supported behavior unless an intentional change has explicit compatibility,
migration and verification evidence. Architecture tests and build gates enforce
their declared scope; passing them does not replace semantic review or prove live
deployment, performance or every external consumer. Record unavailable evidence
and residual risks honestly. Test policy belongs to [TESTING.md](TESTING.md);
security policy belongs to [SECURITY-ENGINEERING.md](SECURITY-ENGINEERING.md).

Significant architectural changes use append-only ADRs. Keep their rationale
separate from a current architecture map and from release execution evidence.
