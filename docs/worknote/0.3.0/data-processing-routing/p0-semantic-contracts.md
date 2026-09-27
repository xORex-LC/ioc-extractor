# P0 semantic contracts and module closure

Status: P0 contract and source-closure audit completed on 2026-09-27 at
`e8cb0aeb83ca6776f771951320163573ac0bbce4`. This is an implementation
boundary for P1/P2, not an activated IOC plan or a claim that the fixtures below
already pass. R0 established the technical Router contracts; P0 closes their IOC
meaning. The [IOC implementation plan](ioc-processing-implementation-plan.md)
remains the sequence for production work.

## Decision and dependency proof

Admit `core/ioc-processing` as the **separate pure IOC preparation module** when
the first production sources move in P1/P2. Do not create an empty module in P0.
Its source-backed compile-time closure is `ioc-domain` and
`ioc-platform-errors` (the existing mapper uses `IocExtractorException` for
unknown registry names). No other platform dependency is admitted without a
demonstrated source import. The module must not depend on `ioc-application`,
`adapter-csv`, `adapter-processing-camel`, Spring,
or a storage/CSV library. Camel's technical `Available(Object)` boundary is
validated and adapted at the outer bridge; Camel objects do not become the IOC
semantic model. No new Maven dependency is needed for this contract slice.

```text
bootstrap/ioc-app -> adapter-processing-camel (technical plan and execution)
bootstrap/ioc-app -> adapter-csv, ioc-application, ioc-processing
adapter-csv      -> ioc-application, ioc-processing, ioc-domain
ioc-application  -> ioc-processing, ioc-domain, platform contracts
ioc-processing   -> ioc-domain, platform-errors
ioc-domain       -> no project module
```

This graph is acyclic. Source evidence for the current obstruction and the
specific relocation boundary:

| Current source | Current coupling | P1/P2 disposition |
|---|---|---|
| `application.pipeline.payload.ClassifiedIndicator` | Imports only domain `Indicator` and `ClassificationDecision`, but its package belongs to application | Move the one classified-value type inward, or replace all consumers with the one new processing value; do not retain two permanent classified models. |
| `application.classification.IndicatorClassifier` | Pure facade over domain `MatchPolicy`; reused by document stage and processed import | Move the facade once into processing; keep `RuleBasedMatchPolicy`, `DefaultIndicatorFeatureExtractor` and normalization in domain. |
| `adapter-csv` `ColumnSpec`, `ConfigurableRowMapper`, `ValueProvider`, `ArtifactFilter`, provider/transform implementations | Mapper, providers and predicates import application `ClassifiedIndicator`; the mapper also imports `IocExtractorException`. `ColumnSpec` itself only imports domain type. Their source packages cannot be dependencies of processing. | Move wire-neutral column specification, one evaluator, provider/condition SPI and value-derived implementations inward after rebinding their input to the moved classified value. Keep context-owned ID semantics outside the value view. Retain CSV serialization/projection in adapter. A temporary adapter facade delegates to this one evaluator and is removed when migration completes. |
| `RowMappingException` / `MappingValueException` | Located in CSV although they encode expected mapping failure, not CSV syntax | Move the semantic failure contract with the evaluator; translate to document diagnostics or import issues at their respective outer boundary. Unexpected exceptions keep their original failure path. |
| `CsvArtifactDefinition`, `CsvArtifactPreparer`, `RowMapper` | Bundle artifact selection, ID strategy, row keys, diagnostics and adapter-facing rows | Split at final mapped fields. Leave ID reservation, `ArtifactWritePlan`, diagnostic delivery and temporary legacy adapter wiring outside processing. Do not move these classes wholesale. |
| `DataframeImportRowMapper`, `CsvProcessedImportRowPreparer` | The first demands record keys before processed preparation; the second infers IOC cells from fixed provider names and imports application contract/key/row types | Keep CSV admission and import authority outside processing. P4 changes their bridge to submit admitted cells to the one semantic evaluator and resolves keys only after final outputs. No second provider-name interpreter. |
| `CanonicalArtifactKeyResolver`, `ArtifactOccurrenceSelector`, import merge and receipt types | Application-owned identity, candidate choice and durable authority | Stay in application/storage. Processing returns fields and ordering evidence; it never hashes a key, chooses a durable winner, reserves an ID or writes a row. |

The source-level closure is feasible because every pure type proposed for movement
has only domain/JDK or platform-errors imports after the
`ClassifiedIndicator` seam is moved. It is **not** yet a Maven compilation
proof: admission in P1/P2 must add parent/reactor,
dependency-management, ArchUnit, coverage and analyzer membership together with
the first relocated production class. A package-only implementation inside
application is technically possible because CSV already depends on application,
but it would co-locate shared preparation with use-case/transaction orchestration
and would not enforce this separate capability boundary. The new module gives
that capability one enforceable owner.

## IOC processing input and value semantics

One invocation uses one pinned, immutable plan and one of two admitted inputs:

- **Document:** one attributed IOC occurrence, before unconditional deduplication
  or classification. Its `original` is the existing refanged/normalized domain
  `Indicator`, with type and `SourceContext`, plus its source text position and
  deterministic tie ordinal. `AttributionOutcome.decisions()` already retains
  every occurrence; `IndicatorOccurrence.orderingPosition()` already encodes
  the rank. The plan never treats `original` as raw Word bytes and never
  recomputes its source label from a derived value.
- **Processed import:** one admitted logical CSV row with named source cells,
  artifact roles, source-row number, merge instructions, requested slot and
  source authority carried by the application. A cell's `ABSENT` means no
  update instruction, `NULL` means an explicit clear, and `VALUE` contains the
  exact admitted text. Neither state is flattened into Java `null` before
  import finalization. Each IOC-bearing cell is an explicitly named original
  input; the row is not treated as one concatenated address. If bindings for
  several cells can feed one target, P2 must reject an ambiguous binding or P4
  must apply the already defined compound-conflict rule after derivation. View
  instances and cache keys for import include the bound source-cell identity;
  equal view names on different cells never alias each other's outcome.

A successful derived view is one immutable typed `Indicator` (value, effective
type, original `SourceContext`) with lineage: view ID, producing operation and
input view. Derivation may change URL to DOMAIN or IPV4, but never overwrites
the original. Document position and import row/cell identity remain invocation
context. `ClassificationDecision` is bound to the **chosen view and configured
policy**, cached only for that invocation/view/policy, and obtained through the
existing `MatchPolicy` evaluator. File indicators use the existing neutral
decision. No global cache or second match-code table is introduced.

View state is four-way: not evaluated, available, absent optional input, or
unavailable with a stable expected-failure reference. An absent optional import
cell is not a malformed URL and is not the same as an explicit NULL cell. A
required consumer of absence/unavailability is blocked; an explicit presence
predicate may inspect optional absence. The producer runs at most once for a
given view in an invocation. A recovery view can use one declared alternate
for allowlisted reasons and retains the primary failure and alternate lineage;
it advertises the alternate's **actual** type, never a fictitious host.
Unexpected exceptions abort the invocation. The Router returns local failure
references; the existing document/import boundary resolves demanded consumers,
emits one final diagnostic per failure occurrence, and applies the existing
checkpoint or row-admission rule. A recovered failure with no strict consumer
becomes a warning; a demanded unrecovered consumer remains an error. Current
import `ImportRowIssue` means rejection, so P4 needs a separate accepted-warning
channel before enabling fallback there. See the
[resolution contract](routing-resolution-and-logging.md) for mode behavior.

## Output, cardinality and identity

Each selected branch returns exactly one of `Prepared`, `Filtered`, or
`Unavailable`. `Prepared` contains the artifact name, branch ID, ordered final
field values (including explicit null cells) and provenance/order evidence;
an immutable ordered representation must preserve these nulls rather than use
`Map.copyOf` on a nullable field map. It has no ID, canonical key, database
operation or already-published diagnostic.
Field provider and gate use the field's resolved view; artifact accept/filter
uses the branch default view; match-code providers use classification of their
resolved view. Source and ID providers use context, not view overrides.

One document invocation may produce at most one candidate **per branch**; many
document occurrences are separate invocations. One processed-import invocation
is one logical row and may produce at most one candidate per authorized branch.
No per-cell Cartesian product, implicit per-branch key namespace or partial
promotion of a rejected logical row is permitted. Routing ALL may yield sibling
candidates; FIRST selects first eligible, not first successfully mapped;
EXCLUSIVE requires proven uniqueness. A filtered branch is a zero-row result,
not a failed view. The Router does not reduce candidates.

After output validation, application resolves the configured record/match keys
from **final fields**, groups by artifact and key, and applies the existing
whole-candidate and registered-field policies. Branch ID and provenance do not
silently enter record identity. The canonical store remains keep-first unless
its separate policy explicitly changes. The hypothetical `(ip, country)` key is
already expressible by `CanonicalArtifactKeyResolver` as a composite formula;
it is a fixture for the architecture, not an actual country column/feed.
Existing rows are not backfilled; TTL removes old active rows only when fixed
lifecycle is enabled.

## Reviewable fixtures for P1-P4

These are expected **candidate/finalization contracts**, not claims about
current executable output. The example plan binds masks and ip_list to `host`
where eligible, address_blacklist to `original`, and classification to each
field's resolved view. Existing artifact gates and key definitions from
`application.yml` still apply. Other enabled artifacts must be included or
explicitly omitted in an admitted complete plan.

| Input and policy | Candidate expectation | Finalization expectation |
|---|---|---|
| Document URLs `https://best-malware.com/a` and `https://best-malware.com/b` | Two host-derived masks candidates with `mask=best-malware.com`; two original blacklist candidates with distinct `forbidden_url` | One masks key under its configured winner policy; two blacklist keys. Preserve both occurrence positions and do not multiply extracted count. |
| Document `10.93.12.187:9090/clean-prometheus/no-virus/true` | Host view `(IPV4, 10.93.12.187)` is eligible for ip_list; original detail remains available to an original-bound branch | `ip=10.93.12.187` key, while an original-bound blacklist keeps its detailed value. P1 must first admit the **whole** address; a prefix match does not pass. |
| Document `https://10.93.12.187/path` | Host view has effective IPV4 type; original remains URL | ip_list may receive bare IP; original blacklist keeps the URL. |
| Document bare `10.93.12.187` | Host derivation is idempotent; original and host retain attribution | Existing bare-IP gates select ip_list and `forbidden_ip`; masks exclusion still applies. |
| Same IP with synthetic `country=AA` and `country=BB` in a future artifact | Two candidates with identical IP and distinct country field | Configured composite `(ip,country)` keeps two keys; configured `ip` key reduces to one according to the artifact's selected policy. No geolocation lookup is inferred. |
| Host operation unavailable; independent original branch selected | Host consumer is blocked, original candidate can prepare; explicit recovered view is a separate binding | Strict demand yields one ERROR; all recovered demand yields one WARN with original cause. Document checkpoint and import atomic row admission remain authoritative. |
| Processed import has one `VALUE` address cell and another `ABSENT` or explicit `NULL` cell | Preserve both presence states and one logical row; derive only from the named `VALUE` input | Validate final fields/key and source authority; absent means no update, NULL retains explicit clear intent. Conflicting derived values for one target reject the row. AS_IS stays unchanged. |
| Invalid whole-cell authority, port or unsupported form | No silent prefix-derived candidate; typed expected failure for input-dependent invalidity | No fallback unless explicitly bound; unexpected parser defects still stop processing. Exact lexical cases and reason codes are P1 work. |

P1 proves parser/extractor parity and exact syntax. P2 proves the one evaluator,
view/classification bindings and startup admission. P3/P4 turn these contracts
into executable document/import fixtures. P5 pins their semantic version across
recovery; neither this note nor R5's synthetic Router profile authorizes
production activation.
