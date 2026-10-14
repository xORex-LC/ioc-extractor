# IOC processing

Processing reads a document, refangs and extracts indicators, attributes sources,
classifies representations and prepares canonical artifact rows. It does not own
file detection, physical database schemas or remote delivery. Documents and
processed CSV imports use required named plans behind application ports;
`as-is` import bypasses IOC preparation.

## Runtime flow

```text
pinned source -> read -> refang -> extract -> attribute
  -> required Router plan -> observation selection
  -> seal repeatable artifact rows -> failure-policy checkpoint
  -> Native canonical commit -> mutable CSV projection
```

The stage order is an application contract, not an operator-defined workflow.
Rules, condition parameters, mapping and failure policy are declarative. Domain,
application and `ioc-processing` remain framework-free. Tika/RE2/J/PSL/CSV/Camel
live in adapters and Spring composition lives in bootstrap.

## Preparation and bounded data

One invocation owns a private SQLite workspace, immutable source/policy pins and
aggregate memory/disk admission. Decoded and refanged UTF-16 text is disk-backed;
regex reads the full `CharSequence` through fixed pages. Indexed extraction
scratch resolves overlap priority and absolute offsets. HTML parsing removes
completed subtrees; DOCX uses SAX. Parser-region, IOC and marker limits reject
oversized preparation before canonical writes. Source scratch closes before
sealing and on failure; it is disposable, not recovery authority.

The workspace reducer retains losing candidates and compares complete final
identity across all artifacts. It preserves first-key order and the entire
winning row. Sealed `RowSource`/`RowCursor` contracts are repeatable and closeable,
so preparation, reservation, mutation and receipt capture do not require a
retained document-wide winner list. Oneshot remains synchronous. Daemon splits
owned `prepare` and ordered `promote`; see [ingestion](ingestion.md).

Attribution chooses the nearest preceding section marker. Ordered occurrences
use a linear cursor (`O(N+S)`); arbitrary occurrence order uses binary search
(`O(N log S)`). Source decisions also supply the unattributed count without
constructing another complete indicator list.

Diagnostic collectors retain bounded ordered details while carrying exact
severity counts separately. Later ERROR/FATAL occurrences still reach failure
policy before reservations. Runner emits retained diagnostics once and adds one
`PIPELINE.DIAGNOSTICS_SUPPRESSED` summary when required. Operation diagnostics
and explicitly enabled TRACE are separate contracts; collector/delivery failures
are not silently ignored.

## Router execution and outcomes

`network.host` derives a bare host/IP; `network.without-scheme` removes only a
validated HTTP(S) scheme and preserves the remaining address text. The latter
classifies detailed results as URL and bare results as DOMAIN/IPV4. Both retain
source attribution and reclassify through the configured policy. Selecting a
view in one import plan does not alter the document plan or shared column mapping.

`adapter-processing-camel` compiles a bounded local plan. Registry-backed
operations, predicates and destinations are resolved before intake; operator
configuration cannot supply endpoint URIs, scripts or a custom expression
language. Conditions compose registered leaves with AND/OR/NOT. Dependency order
and view/recovery references are checked before starting routes.

The compiled selector evaluates FIRST/ALL/EXCLUSIVE before destination calls.
Conditions short-circuit. Required views are invocation-local and evaluate at
most once. Available, absent and expected-unavailable outcomes are distinct;
unavailability is not automatically boolean false, especially under NOT.
FIRST chooses the first eligible branch, ALL retains eligible branches, and
EXCLUSIVE rejects ambiguous eligible selection. A branch blocked by a required
representation carries its reason instead of becoming an ordinary no-match.
The detailed operator syntax and unavailable/no-match policies are in
[processing routes](../guides/ioc-processing-routes.md).

Explicit `view.recover` uses a registered reason allowlist and a separate earlier
alternate view. A recovered outcome retains the original failure and effective
view identity. Application decides diagnostic severity and failure policy;
Camel does not retry data or storage operations. Destinations return a typed
candidate, filtered outcome or expected failure. Unexpected exceptions abort
the invocation.

The runtime binds fixed local Camel endpoints after context startup. After
selection and resolution of required views, it sends immutable branch input
sequentially through its existing `ProducerTemplate`. Each native consumer has
its own Exchange and UnitOfWork; stopped routes are refused. There is no extra
Recipient List/dispatch/aggregation route or second Java execution engine.
Bootstrap owns readiness and bounded shutdown with active-call drain. Routing
trace uses policy fingerprints and plan/view/branch context, without IOC values;
MDC restores the parent scope even on exceptions.

## Observation selection and identity

The shipping `original-document` plan uses original values and
`observation-selection: retained-observations`. KEEP_FIRST passes the original
observations admitted by `ioc.pipeline.deduplicate` without grouping mapped
collisions. With deduplication disabled, repeated prepared rows and their ID
reservation/provenance behavior remain observable. The lifecycle writer still
rejects duplicate final keys before reservation; this plan does not conceal
that limitation ([OUT-3](../KNOWN-ISSUES.md)).

`final-key` retains occurrences until mapping and selects KEEP_FIRST winners by
filled artifact identity. It supports detailed URL -> host/IP collapse.
LAST_NONEMPTY in either mode examines every relevant occurrence and selects one
whole row with a nonempty configured selection field. Identity is computed
**after** preparation; a configured composite key can preserve distinct rows
with the same IP and different metadata. There is no hard-coded country or IOC
uniqueness rule. Empty plans for intentionally skipped artifacts still satisfy
receipt marker/count contracts.

Policies affect new incoming data; enabling cleanup does not rewrite existing
canonical records. They expire through their existing TTL. Source registration
precedes processing and survives retry/restart; ordered mutable fields use that
durable rank plus occurrence position, independently of completion order.
The `ioc_aggregate` artifact uses configured carrier identity and ordered
nonempty `name` updates; it is not synthesized from the other artifact tables.

## Shared mapping and semantic reuse

`ioc-processing` owns one mapper for document and processed import: column
providers, transforms, classification facade and exact structured-cell parsing.
Column `type` describes public storage, not a value provider. Providers/transform
names and arguments bind once at construction; gates and transform order remain
unchanged. Deferred `id` materializes only at the write boundary. Mapping is
side-effect-free. Expected data-dependent rejection becomes an element
diagnostic; programming/storage failures abort the run.

Classification rules and mask codes come from the operator catalog, including
classification of derived views. `NetworkAddressParser` accepts supported
HTTP(S), DNS/IPv4 and optional port/path/query/fragment shapes;
`NetworkHostDeriver` creates a separate IOC with current source context.
`ExactIndicatorParser` requires a complete structured-cell match. Document
extraction continues to search within text. Fragment remains a feature, so a
fragment-bearing IPv4 cannot pass a bare-IP predicate.

A document owns one processing session; processed import owns one session per
staging attempt after contract validation. Successful pure classification and
host derivation may be reused. Classification keys include value, type, source
label and section, and require the same classifier-policy identity. Host cache
entries contain value/type only; source context is attached anew. Mapping,
positions, diagnostics, rows and time are never cached.

The bounded LRU budget is 256 entries and 1 MiB estimated retention, split
equally between classification and host derivation, with at most 64 KiB per
entry. Oversized entries bypass caching without eviction. Original and derived
classification share their partition. This budget is not whole-process memory;
every occurrence still runs mapping. Session closure precedes seal/checkpoint
and also occurs on failure. Receipt recovery and `as-is` import do not open it.

## Processed-import composition

Every processed contract declares a `processed-route`, input IOC cells and
allowed output targets. Static permissions and effective merge policies pin
the full immutable compiled contract; the same ID cannot replace its definition,
version or fingerprint. Each row still validates contract/cell authority.

Multiple input cells contribute independently. A provider's inapplicable `null`
is no contribution, not an explicit CSV NULL. Contributions are merged before
route-managed carriers are replaced: URL -> IP removes the old URL carrier
when that input actually produces another carrier. An independent input's
contribution and explicit CSV NULL are preserved. Conflicting actual values
reject the compound logical row rather than losing an input silently.

Source authority stays with the import contract. Allowed source transforms and
retained source values must use the same `when-type`/`when` applicability as
mapping, preserve ABSENT, and reject unrelated replacement. Record/match keys
are calculated only after preparation. Compatible COALESCE participants retain
warnings in the canonical receipt, even when another participant represents
the merged row. Fallback warnings and rejection issues remain distinct through
sealed stage, commit and terminal receipt recovery.

Changing a document-policy fingerprint is refused while admitted/claimed work
remains unresolved. Import pins contract version/fingerprint per delivery.
Compatible sealed stages can be reused without reading CSV; after commit,
finalization uses the receipt. See [dataframe import](dataframe-import.md).

## Failure and extension contracts

| Boundary | Behavior |
|---|---|
| Parsing/admission | Typed source failure; no canonical mutation |
| Expected mapping rejection | Element diagnostic; failure policy decides the prepared run |
| Rejected checkpoint | No ID reservation or canonical write |
| Unexpected exception/storage failure | Run failure; not collect-and-continue |
| Projection after commit | Canonical truth remains; recovery retries projection |
| Dry run | Decisions/preparation only, no canonical commit or projection |

RE2-compatible patterns use no lookaround/backreferences. Supported IOC types
are IPV4, DOMAIN, URL, MD5, SHA1 and SHA256. HTML/legacy charset, PDF, DOCX and
XLSX have explicit format contracts; other Tika parsers are best effort.
Source charset and CSV charset are independent. Mutable projection may replace
unmappable characters with a warning; immutable export uses strict encoding
because bytes enter the manifest hash.

Add an artifact through mapping/identity configuration when existing registries
suffice. New value semantics require a small `ioc-processing` component and
explicit registry/preflight tests; a new external format belongs in an adapter.
Changing stage order, winner rules, diagnostics, commit scope or session ownership
requires updating this guide and the corresponding contract tests.

## Sources of truth

- [IocExtractionService](../../core/ioc-application/src/main/java/com/iocextractor/application/service/IocExtractionService.java) and [AppConfig](../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/AppConfig.java).
- [Camel adapter reference](../../adapters/adapter-processing-camel/README.md) and [processing module](../../core/ioc-processing/README.md).
- [Production configuration](../../bootstrap/ioc-app/src/main/resources/application.yml).
- [GoldenPipelineIT](../../bootstrap/ioc-app/src/test/java/com/iocextractor/GoldenPipelineIT.java), [CustomerRoutingPipelineIT](../../bootstrap/ioc-app/src/test/java/com/iocextractor/CustomerRoutingPipelineIT.java).
- [RouterSelectedImportDeliveryIT](../../bootstrap/ioc-app/src/test/java/com/iocextractor/bootstrap/RouterSelectedImportDeliveryIT.java), [NativeDeliveryLifecycleIT](../../bootstrap/ioc-app/src/test/java/com/iocextractor/bootstrap/NativeDeliveryLifecycleIT.java).

Related: [storage](storage.md), [native capacity](native-capacity.md),
[observability](observability.md), [ADR 0034](../ADR/0034-required-router-processing-plans.md),
[ADR 0036](../ADR/0036-sealed-document-preparation-workspace.md),
[ADR 0039](../ADR/0039-streamed-document-source-processing.md),
[ADR 0040](../ADR/0040-native-sequential-preparation-dispatch.md).
