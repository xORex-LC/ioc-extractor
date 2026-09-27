# Data-processing models and implementation patterns

Task-level mapping and source-backed work packages: [Camel task design](camel-task-design.md).

Status: revised research, 2026-09-27. NiFi and Beam are architectural references,
not proposed deployments. Runtime selection has been reopened: compare the JDK
option with Camel owning preparation execution and replacing adjacent dispatch.
See [Camel applicability and migration](camel-migration-assessment.md) for source
seams, dependencies, failure contracts and qualification gates. Earlier JDK-only
recommendations remain an alternative, not an implementation commitment.

## NiFi: work units, operations and execution control

NiFi separates FlowFile content/attributes, processors, named output
relationships and provenance. Its ProcessSession supports commit/rollback of
FlowFile operations. The developer guide distinguishes whole-unit routing from
routing records inside a unit, and describes one-to-many routing with explicit
unmatched outcomes. Source: [NiFi developer guide](https://nifi.apache.org/nifi-docs/developer-guide.html).

Our adaptation is an immutable observation/row context, typed operations and
explicit accepted/filtered/invalid outcomes. Source, delivery, rank and policy
version are metadata; they are not automatically identity columns. Branches
derive independent values. Reuse Envelope, preparation results, diagnostics and
decision tracing. Document observations and structured import rows retain their
different cardinality and provenance contracts.

Do not copy FlowFile repositories or ProcessSession: canonical transactions,
private import workspaces and receipts already own durability. A NiFi session
is not a transaction over arbitrary external I/O. Collecting preparation
results before our failure checkpoint is not a new transaction implementation.

NiFi separates backpressure and queue prioritization from processor behavior.
For us, resource limits belong to existing intake/executor/workspace owners;
scheduling priority cannot determine a canonical winner. Source:
[NiFi user guide](https://nifi.apache.org/nifi-docs/user-guide.html).

## Beam: transformation, grouping and reduction

Beam models collections and transformations, including branching, GroupByKey
and Combine. Its guide describes collection immutability, schemas and constraints
on combination functions. Source: [Beam programming guide](https://beam.apache.org/documentation/programming-guide/).

The useful local model is:

```text
derive representation -> map fields -> calculate configured record key
                      -> group equal keys -> apply winner/field policy
```

The last three responsibilities already have implementations in our service.
Reuse them rather than building a Beam-shaped grouping subsystem. Logical
grouping does not require retaining the entire import in heap: private SQLite
staging and the canonical writer remain physical execution owners.

Separate the processing description from execution, so semantics do not depend
on partitioning or thread completion. Beam does not promise one universal
processing order. Source: [execution model](https://beam.apache.org/documentation/runtime/model/).

A future parallel whole-row latest selection can select the maximum explicit
observation rank, rejecting contradictory equal-rank evidence. Field-wise
combination is a different policy. Only associative/commutative policies may be
freely regrouped/reordered; replay needs idempotent application or receipts.
For example, fill-missing against existing state must not be presented as an
unordered reduction without proving its semantics. These are our design
conclusions, not claims that Beam implements our canonical mutation policies.

Beam windows/event-time lateness are not lifecycle TTL. Retain active-lifecycle
matching and registered observation order; do not introduce watermarks, a window
engine or new global group state for host cleaning.

## EIP/Camel: branch selection versus operation composition

Use [Content-Based Router](https://www.enterpriseintegrationpatterns.com/patterns/messaging/ContentBasedRouter.html)
for conditional selection, [Recipient List](https://www.enterpriseintegrationpatterns.com/patterns/messaging/RecipientList.html)
for independent artifact outputs, and [Routing Slip](https://www.enterpriseintegrationpatterns.com/patterns/messaging/RoutingTable.html)
as a reference for chosen operation sequences. Exclusive routing alone does
not describe multi-artifact output.

Camel's [YAML DSL](https://camel.apache.org/components/4.22.x/others/yaml-dsl.html)
illustrates separating route descriptions from Java operations. The lesson is
explicit composition and validation. It does not require exposing Camel DSL
or installing a second executor in this service.

Our accepts, predicates, column providers and ordered transforms already form
a declarative language. Extend its bindings with named views and operation
capabilities. Do not create another DSL that duplicates artifact schemas,
identity columns or mutation policy. Keep one configuration admission path.

## Other references and boundaries

| Reference | Useful model | Local application / limit |
|---|---|---|
| [Hop architecture](https://hop.apache.org/docs/architecture/) | Pipelines vs workflows | Separate row semantics from delivery/recovery |
| [Spring Integration transactions](https://docs.spring.io/spring-integration/reference/transactions.html) | Thread-bound transactions | Async branches must respect application commit boundaries |
| [Spring Batch steps](https://docs.spring.io/spring-batch/reference/step/chunk-oriented-processing/configuring.html) | Chunking/restart metadata | Incremental staging does not change atomic import promotion |
| [Flink sink guarantees](https://nightlies.apache.org/flink/flink-docs-stable/docs/connectors/datastream/guarantees/) | Participating sinks | Framework recovery does not replace our receipts |

These are pattern-level comparisons, not dependency, performance or release
qualification. No platform adoption is proposed.

## Parser implementation reference

JDK 21 [URI](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/net/URI.html)
provides component parsing without DNS lookup. It is a candidate inside the
existing domain parsing boundary, not a complete IOC grammar. Scheme-less
values, malformed authorities and Unicode need explicit rules. URI.normalize()
does not extract a host.

Share parsing between feature extraction and host derivation. Reuse existing
IndicatorNormalizer, Refanger and HostClassifier responsibilities; PSL
classification stays separate. Host extraction must preserve subdomains.

## Shared verification baseline

Exercise existing extension points with identical document/import fixtures:
original and cleaned branches; country-sensitive keys; configured winners;
source authority; ambiguous match rejection; stable rank under replay; bounded
memory/staging; existing diagnostics and startup validation.

Measure optimizations against the same baseline/corpus. Introduce a library
only after identifying an uncovered responsibility, its sole owner, replacement
scope and dependency cost. No new engine, broker or library extraction is
presumed. See the [reuse inventory](reuse-inventory.md) for concrete owners.

## Router module decision

The [boundary analysis](router-boundary.md) specifies option A: JDK-only
destination selection with the existing ETL. The newer
[Camel migration assessment](camel-migration-assessment.md) defines option B and
reopens the runtime/module decision before implementation. The IP-country fixture is hypothetical, not a production finding.
