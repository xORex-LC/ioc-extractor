# Configurable preparation plans

> Runtime selection reopened on 2026-09-27: see [Camel migration assessment](camel-migration-assessment.md).
> The JDK-only design below is option A, not a frozen implementation decision.
> Qualify option B (Camel preparation execution) before adding a routing module.

Status: revised architecture and delivery plan, 2026-09-27. Decision records
below are drafts; accepted decisions should later be published as sequential
ADRs. No production behavior changes with this document.

## Requirement and current evidence

Allow `https://best-malware.com/troyan.exe` to become `best-malware.com`, and
`10.93.12.187:9090/clean-prometheus/noе-virus/true` to become `10.93.12.187`,
only for configured destinations/fields. Preserve other representations when
needed. Apply the policy to document extraction and explicitly contracted import.

| Current code seam | Consequence |
|---|---|
| `ConfigRegistryCatalog`: value/address providers; lower/lower-host/upper/strip-prefix transforms | No supported host-only operation; lower-host retains address detail |
| [ConfigurableRowMapper](../../../../adapters/adapter-csv/src/main/java/com/iocextractor/adapter/out/sink/csv/ConfigurableRowMapper.java): type/condition gates precede cell transforms | A new string transform alone cannot reroute a URL into an IP field |
| [RuleBasedMatchPolicy](../../../../core/ioc-domain/src/main/java/com/iocextractor/domain/classify/RuleBasedMatchPolicy.java): first matching configured rule over extracted features | Codes are operator-owned; facts still depend on the selected value |
| `IocExtractionService`: classify before artifact preparation | One classification currently precedes independently formatted outputs |
| [CsvProcessedImportRowPreparer](../../../../adapters/adapter-csv/src/main/java/com/iocextractor/adapter/in/csv/CsvProcessedImportRowPreparer.java): CSV definitions, concrete mapper, hardcoded IOC provider names | Adding a provider alone does not establish shared import semantics |
| Canonical keys are derived from prepared fields | Host reduction changes identity, deduplication and potentially mutable-field conflicts |
| [ProcessingPolicyFingerprint](../../../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/ProcessingPolicyFingerprint.java): versions current processing policy | New view/route/operation semantics must participate in policy identity |

The existing pipeline is a useful execution shell. Its ordered stage runner is
not currently a record-routing DAG engine; see the
[LIB-4 assessment](../libs/lib-4-etl-analysis.md). Module placement is evaluated explicitly in the [module design](module-design.md).
A dedicated IOC processing module is a candidate; independent generic routing
is now recommended by the [Router analysis](router-boundary.md). Reuse does not mean keeping all responsibilities in one JAR.

### Confirmed identity and consolidation contract

The owner confirmed that cleaned URLs with the same host may collapse. Equality
belongs to each artifact's configured final key, not to a universal IOC value.
Country-sensitive records are a hypothetical extensibility example, not a new
production artifact or a discovered current defect.

Existing `ioc.artifact-identity` remains the source of record/match formulas;
existing `write-policy` remains the artifact selection/update entry point.

| Scenario | Key | Expected result |
|---|---|---|
| Two paths reduced to one domain | domain field | One record, winner chosen by configured policy |
| One domain observed from two sources | domain field | One record unless source is explicitly part of identity |
| Same IP, different countries | IP + country | Two records |
| Same IP, different countries | IP only | One record; country is handled by the selected conflict policy |

Name, first/last seen and source do not automatically distinguish records.
Their role is configurable. Neither field names nor perceived importance should
be hardcoded into deduplication. Preserving the latest whole row is distinct
from taking independently selected values for different fields.

Existing code already supports composite keys, alternative import match keys,
whole-candidate last-nonempty selection and ordered field updates. It does not
provide every possible row replacement policy. See the [reuse inventory](reuse-inventory.md)
for verified capabilities and limitations. In particular, country-sensitive
identity also requires suitable import match keys; an IP-only lookup can be
ambiguous or conflict with stable identity.

### Classification clarification

The operator can configure match codes and rule precedence. The proposal must
preserve that freedom. The open question is which facts a rule receives:
original `domain.com/file`, or derived `domain.com` with no path. Classification
may intentionally use either view; it must be explicit and inspectable. No new
hardcoded mapping from cleaned host to a particular match code is proposed.

### Parsing clarification

Current feature parsing stops authority at `/` or `?`, but not `#`; it recognizes
a numeric port after the first colon. Thus `https://domain.com#section` can give
`domain.com#section` as host, and `https://user:pass@domain.com:8443/a` can retain
credentials and port in host. These are parser limitations, not route priority
problems. A production host operation needs a tested address contract. IPv6,
IDN, trailing dots, invalid ports and malformed inputs require explicit
support/rejection decisions; IPv6 is not currently an IOC type.

## Four independent kinds of order

| Kind | Example | Authority |
|---|---|---|
| Operation dependency | Parse/derive before predicates using derived host; keys after final identity fields | Compiled preparation plan |
| Rule selection | First matching classification rule; all matching artifact recipients | Named rule/route policy with explicit match mode |
| Work scheduling | Intake fairness, bounded parallel preparation, backpressure | Application runtime/executor |
| Data precedence | Which name survives when two URLs collapse to one key | Canonical merge policy and registered observation rank |

A single numeric `priority` cannot safely represent all four. In particular,
completion order must not replace
[ADR 0030](../../../ADR/0030-registered-observation-order-for-artifact-fields.md)
precedence `(admission order, occurrence position)`. Existing keep-first artifacts
retain their own merge contract; the aggregate's mutable-field policy must not
silently become global. Host collapse follows the configured artifact identity,
even when there are no competing mutable fields.

## Proposed processing model

```mermaid
flowchart TD
    D[Document observations] --> P[Typed preparation plan]
    I[Processed import row and contract] --> P
    C[Validated configuration] --> P
    P --> O[Original address view]
    P --> H[Derived host view]
    O --> A[Select, classify and map branch A]
    H --> B[Select, classify and map branch B]
    A --> R[Prepared rows, provenance and diagnostics]
    B --> R
    S[As-is import: explicit contract transforms] --> R
    R --> K[Validate identity and failure policy]
    K --> W[Existing canonical mutation path]
    W --> E[Projection and export coordination]
```

This is a conceptual convergence of policy. Document writing and import
promotion retain their respective existing transaction/recovery paths. The
diagram does not authorize a new universal transaction implementation.

1. Keep original observation and source/occurrence identity immutable. A parsed
   address holds components, not just a string; a derived view has a value,
   effective IOC type and features. Host extraction preserves subdomains.
2. Name reusable views, such as `original` and `host-only`. Operations declare
   input/output types, deterministic behavior, cardinality and failure behavior.
   Destructive derivation does not discard original evidence from the context.
3. Each artifact branch selects its view and predicates. Fields can reference
   another named view for display, with explicit type/condition evaluation
   context. Classification profiles consume a named view and return configured
   codes. A branch is not an implicit mutation of all subsequent branches.
4. Compile references, dependencies, result types, ordered transforms and
   route modes at startup. Reject cycles, ambiguous binding, unauthorized
   destinations, incompatible types and unsupported identity transformations.
   Use explicit `all`, `first` or `exclusive` semantics, plus no-match behavior.
5. Prepare rows without durable business mutation. Validate final fields and
   derive versioned keys before invoking the established write path. Evaluate
   all required branch diagnostics at the failure checkpoint; configuration
   cannot route around that checkpoint or directly invoke a repository.

There are two useful operation categories: semantic derivations that affect
type/features/routing, and field formatting. Formatting of an identity field
still affects identity and must finish before keys are computed. Stable node
and branch identifiers preserve deterministic occurrence provenance; scheduling
or incidental collection iteration must not manufacture observation precedence.

### Illustrative configuration, not an accepted schema

```yaml
views:
  original: {from: observation}
  host-only: {from: original, operations: [parse-address, extract-host]}
branches:
  cleaned-ip:
    artifact: ip_list
    input: host-only
    accept-types: [IPV4]
    fields:
      ip: {from: host-only.value}
  original-blacklist:
    artifact: address_blacklist
    input: original
    mapping-profile: existing-address-routing
```

For a scheme-bearing IPv4 URL, the first branch can use an effective `IPV4`
view while the second keeps the original URL. A masks branch can separately
select a classification profile and the view it classifies. Independently
removing scheme, port, path/query/fragment should be typed operations with
validated composition, not ad-hoc regular-expression replacements. The exact
catalog and allowed combinations are a requirements deliverable.

## Import, identity and recovery boundaries

- **Processed import:** reuse semantic preparation operations, but retain the
  enclosing row and field provenance. Multiple cells are not automatically
  independent observations. Reject contradictory derived cells; do not produce
  a Cartesian product or correlate unrelated carriers. Artifact targets remain
  bounded by the pinned source authority and versioned import contract.
- **As-is import:** permit only transformations explicitly selected by its
  contract. Do not silently apply the document policy. Preserve the distinction
  between absent, explicit null and a value, and the contract's merge behavior.
  Value transforms must not convert absence into a value or a parse failure into
  an explicit clear. Invalid data produces the existing typed failure outcome.
- **Identity collapse:** `/a` and `/b` may become the same host key. Specify
  keep-first/mutable-field behavior, duplicate branch results and equal-rank
  conflicts. Retain source occurrence ranks through derivation. Confirm TTL
  renewal, revision changes and slot assignment through existing canonical rules.
- **Policy pinning:** include graph/view/rule/operation semantic versions in the
  fingerprint. Determine how in-flight documents, import snapshots and sealed
  workspaces retain/recover that plan. If the pinned implementation is unavailable,
  block explicitly or drain before activation; never silently retry with new rules.
- **Activation:** choose future-observations-only behavior versus explicit
  historical reprocessing. The former can leave detailed and cleaned keys
  coexisting. The latter needs collision handling, provenance, lifecycle and
  export-slot decisions. A changed CSV projection alone cannot migrate DB truth.
  Existing immutable export slices must remain immutable.
- **Atomicity:** retain the failure checkpoint and managed import's single
  cross-artifact canonical promotion/receipt. Coordination registration may
  precede preparation; no new business-row mutation may bypass the checkpoint.

## Ownership and extension points

| Layer | Responsibility |
|---|---|
| Domain | Address components, pure derivations, feature/classification contracts |
| Application | Typed plan, route selection, import row context, preparation results, policy identity and use-case orchestration |
| CSV adapter | Wire parsing/encoding and translation to/from application values |
| JDBC adapter | Existing canonical identity, precedence, lifecycle and receipt transactions |
| Bootstrap | Typed configuration binding, registry assembly, eager semantic validation |
| Proposed processing module | Own relocated pure preparation if its dependency closure is admitted; see module design |

Extract shared semantics from `CsvProcessedImportRowPreparer` and CSV mapper
coupling into cohesive application contracts. Replace magic provider-name
recognition with declared operation/provider capabilities. Avoid a single
router class owning parsing, mapping, scheduling and persistence. Add operations
through narrow registries; prevent generic maps/framework message types from
becoming the core API. Evaluate module extraction using the [module design](module-design.md):
`ioc-processing` can own shared pure preparation; a generic `platform-routing`
has a proposed distinct JDK-only contract in the Router analysis. Preserve acyclic dependencies and
one implementation when moving existing behavior.

## Draft decision records

### D1 — Shared policy, separate intake and commit ownership

**Status:** proposed. **Context:** document and import need equivalent address
semantics but different cardinality/recovery rules. **Decision:** share typed
preparation operations/plans; keep intake adapters and canonical commit use cases.
**Alternatives:** duplicate transforms; normalize globally; unify all deliveries
into an unrestricted graph. **Consequences:** explicit common contracts and
some refactoring, while row authority and recovery remain reviewable.

### D2 — Start with branches/sequences, compile a bounded dependency graph

**Status:** proposed, subject to the extension-point and module-boundary review.
**Context:** both YAML profiles and operator-authored graphs are acceptable to
the owner; cost and growth matter. **Decision:** support named views, configurable
sequences and explicit fan-out first. Internally validate dependencies so a later
graph syntax can reuse the same operations. **Alternatives:** fully arbitrary
graph now; fixed transform-only patch; external-engine adoption. Existing local
mechanisms are the implementation baseline; NiFi/Beam inform the model.
**Consequences:** current requirements are covered without general joins,
cycles or visual editing. Cross-record joins require a separate state, window,
memory, timeout and recovery contract; a future graph is not a free syntax change.

### D3 — Keep durable coordination outside the configurable data graph

**Status:** proposed. **Context:** retries, lifecycle and imports have existing
durable owners. **Decision:** plans cannot redefine commit, receipt, lifecycle
or replay boundaries; events remain hints. **Alternatives:** engine-owned
per-node durable execution or external ETL platform owning delivery end to end.
**Consequences:** less operator freedom around side effects, but no duplicate
truth or accidental multi-database transaction promise. External integration
platform adoption would require a separately accepted handoff contract.

## Runtime, performance and operability

Compile once per policy version, not per IOC. Reuse address/features for the
same observation and view within a bounded preparation scope. Avoid unbounded
global caches, one task per field, unbounded fan-out and parallel writes used
as a substitute for CPU optimization. SQLite allows only one writer at a time;
more workers do not remove this bottleneck. Source:
[SQLite isolation](https://www.sqlite.org/isolation.html).

Start with deterministic sequential preparation. Add bounded parallelism only
after profiling, retaining stable observation order and cancellation/resource
ownership. Preserve streaming/private-workspace behavior for imports; do not
materialize the entire delivery to support graph execution. Measure rows/s,
p95 delivery latency, heap/allocation, staging disk, transaction duration and
writer waits against the same corpus and hardware. Use SQLite query plans for
changed identity lookups, not PostgreSQL-specific tuning advice.

Extend existing validation, decision tracing and dry-run with selected view, route/rule IDs,
derived type, field binding, rejection and identity-collision summary. Default
diagnostics must avoid credential/query leakage and IOC-valued metric labels.
Bound route count, depth and output multiplicity; allow only registered
operations and authorized artifacts. Configuration is not arbitrary code or
an endpoint URL sourced from an IOC. No DNS/network access during parsing.

## Delivery slices and relative cost

| Slice | Reviewable result | Dependencies |
|---|---|---|
| P0 — Semantic contract | Example matrix, input grammar, view/routing/cardinality rules, activation choice | Owner discussion |
| P1 — Reuse and module closure | Exact move/extend map, one owner per mechanism, package vs ioc-processing decision, fixture-based extension probe | P0 |
| P2 — Address operations | Tested parser contract and typed derivations; existing behavior remains opt-in compatible | P0, chosen boundaries |
| P3 — Configuration compilation | Extend existing catalogs/binder with views and dependencies, fingerprint and existing tracing | P1–P2 |
| P4 — Shared preparation and policy | Move shared behavior once; reuse key/selection/merge owners; explicit as-is transforms | P3 |
| P5 — Canonical/recovery qualification | Collision/rank/TTL/receipt tests; policy restart and activation procedure | P4 |
| P6 — Release integration | Operator guide, capability docs, accepted ADRs, configuration examples, full gates | P5 |

P0/P1 are bounded discovery work, not production implementation. Do not put
the feature into a verified release cell before its acceptance evidence exists.

| Scope option | Current requirements | Additional work and cost drivers |
|---|---|---|
| One host-only string transform | Partial | Small change, but routing/type/import/identity gaps remain |
| Configured branches + typed operation sequences | Full, subject to P0 semantics | Medium project: shared preparation refactor, compiler, recovery and activation tests |
| Operator-authored acyclic graph | Full | Adds graph authoring/schema, typed edges, result merge/cardinality, graph diagnostics and version migration; materially larger |
| Arbitrary graph with joins, cycles, UI and durable nodes | Beyond current requirement | Separate platform scope: state stores, bounded joins, recovery, authoring and operations |
| Dedicated Maven capability | Same semantics as package variant | Adds relocation, dependency/boundary enforcement and build-report scope; improves compile-time separation if cohesive |

These are relative estimates, not schedule commitments. P1 should produce a
work breakdown and engineering-day range for the selected option. Unknown
historical migration, throughput/SLO targets and authoring UX make a precise
calendar estimate unreliable today. Budget discovery separately. External
framework selection is not the critical path; existing-service extension and
identity/recovery qualification are.

## Acceptance and open decisions

Required tests cover URL/domain/IP with and without schemes, ports, paths,
queries/fragments, defanging and punctuation; userinfo/invalid authorities;
unaffected hash/metadata fields; subdomain preservation; original and cleaned
outputs together; classification on the selected view; route no-match and
multi-match cases; duplicate identities and opposite completion order; import
compound/null/authority cases; crash after canonical commit; changed-policy
restart; TTL renewal and immutable export correctness.

Baseline compatibility must compare complete outputs and canonical effects
under the unchanged configuration. Run focused tests, then `make verify` and
`make pmd-analysis` on the final implementation; inspect analyzer findings.
Run `make pmd-watchlist` if resource ownership or class/method growth warrants
it. Performance measurements and external transport evidence remain separately
labeled; this research does not supply them.

Confirmed decisions: normalized final values participate in configured artifact
identity; selected key columns determine multiplicity; non-key data follows
configurable consolidation policy; NiFi/Beam are design references; existing
owners must be reused; new Maven modules are valid boundary options.

Remaining decisions:

1. Proposed policy scope: retain keep-first/last-nonempty and ordered field
   updates, and add explicit whole-record latest-registered selection if the
   latest-source scenario requires replacing all non-key fields. Confirm its
   mutable-column/null/origin semantics; it must not overwrite key fields,
   generated IDs or internal lifecycle facts.
2. Which classification view is the default, and where may an operator override it?
3. What happens to already stored detailed values when cleaning is enabled?
4. Does P1 qualify an independent IOC processing module, and does the Router contract
   qualification validate the proposed independent platform-routing module?
5. Are cross-record joins needed beyond equality-based consolidation, and what
   input sizes/throughput targets bound the execution model?

### Draft decision D4 — Reuse configurable identity and mutation owners

**Status:** proposed implementation decision; business semantics confirmed.
**Context:** per-artifact key formulas and candidate/field policies already exist.
**Decision:** normalization feeds final fields into those formulas. Extend the
existing policy family for missing strategies; retain import authority and
registered rank as independent gates. **Alternatives:** new router-owned dedup
store, global IOC identity, replacing whole-row policy with field-wise updates.
**Consequences:** one canonical identity implementation, explicit consolidation,
additional activation tests for changed keys/matches and coherent module moves.
