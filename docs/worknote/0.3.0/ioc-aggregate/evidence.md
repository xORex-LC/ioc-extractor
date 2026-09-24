---
title: "DATA-AGGREGATE-01 — Evidence ledger"
version: "0.3.0"
status: "P0-P4 implementation and exact-HEAD qualification evidence"
document_type: "Evidence ledger"
source_of_truth: false
language: "en"
---

# DATA-AGGREGATE-01 — evidence ledger

## Analysis baseline — 2026-09-21

- Checkout: `r030-libraries`, HEAD `e3f88ccf69ccb5147b98169ff6c2ba9742fb0f7a`.
- Version: `0.3.0-SNAPSHOT`; tracked worktree clean before documentation edits.
- `make context`: passed; local verify and PMD evidence refer to `3307d1e2`,
  both freshness flags false. No current full-gate claim.
- Inspected repository instructions, root architecture/policy documents,
  release baseline/status, TTL/import worknotes, processing/storage/export docs,
  configuration assembly, mapping and canonical ordinary mutation code.

## Local sample inspection

Python standard-library CSV parsing of local `dataframe/IOC_aggregate.csv`
found 9,557 data rows, all five columns wide: 9,035 hash-bearing rows and 522
IP-bearing rows. Both match columns were NULL throughout. There were 219 names,
no exact duplicate rows and no repeated raw `(ip_address, hash)` pair.

Ignoring letter case, 65 hash values failed the expected hexadecimal
MD5/SHA1/SHA256 length/content check. One IP had a trailing dot. This was a
structural/data inspection, not an execution of the service's parser or an
assertion about its precise rejection outcomes.

The sample is gitignored local material; these counts are an analysis snapshot,
not portable qualification evidence or a substitute for consumer fixtures.

## Implementation and validation status

No production/configuration edits, feature tests, runtime smoke, full verify,
PMD run or external/deployment qualification performed for this feature.
Documentation initialization checks passed: `make docs` reported 987 total
links, 377 unique, 852 OK, 135 excluded and 0 errors. A separate bundle-local
relative-link check found no missing targets. `git diff --check` passed.
No production validation is inferred from these documentation checks.

## Discovery follow-up — 2026-09-21

`make context` confirms `feature/dataframe/new-artifact`, same HEAD, no upstream,
and transferred documentation changes. Inspected `MarkerSourceAttributor`,
classpath section-marker regexes, managed-import documentation and explicit
contract/routing declarations. No production changes or feature tests.

## Decision-recording pass — 2026-09-21

Rechecked branch/HEAD with `make context`; unchanged from the feature-branch
checkpoint. A standard-library CSV census classified all 219 distinct labels:
119 BIB, one request, 97 dated underscore FSTEK, one undated underscore FSTEK,
and one spaced FSTEK label. The production relevance is owner-provided evidence,
not an inspected stand. Recorded accepted requirements and separated remaining
technical proposals; production code/configuration remain unchanged.

## Concurrency clarification

Reviewed `IngestFlowConfiguration`, `FileSourceMessageHandler`,
`IocConfigPreflight`, `IngestionService` and `DataframeImportDrainCoordinator`.
Confirmed sequential normal document dispatch, separate delayed retry execution,
per-content-key exclusion and a separate durable global import lane. No runtime
concurrency test was executed. Updated the architecture assessment to correct
the earlier generic parallel-document example.

## Accepted behavior checkpoint

Recorded owner acceptance of last labeled occurrence within a document,
configurable empty-name preservation and durable registration priority unchanged
by errors/retries/restart. This is requirements evidence, not implementation or
runtime qualification. `make context` confirms the same feature branch and HEAD;
production code remains unchanged and full verify/PMD evidence remains stale.


## Technical-design review — 2026-09-22

`make context` reconfirmed feature/dataframe/new-artifact at
e3f88ccf69ccb5147b98169ff6c2ba9742fb0f7a. Existing verify and PMD remain stale.
Prepared technical design, dependency alternatives and P0–P7 implementation
slices; expanded planned verification to cover admission, mutation and recovery.

Read-only integration reviews examined prehash document claims, separate import
coordination, oneshot admission, canonical mutation outcomes, receipt reuse and
processed-import source bindings/fingerprints. Review corrections explicitly cover
missing-registration resume, terminal handshakes, unresolved crashed CLI records,
file replacement ambiguity and stage-policy drift. These are proposed remedies,
not executed defect fixes or runtime evidence.

Library alternatives are documented with primary-source links. No dependency,
production Java, configuration preset, database or published library was changed.
The design leaves owner questions explicit and does not claim production readiness.

Documentation checks for this design pass: `make docs` passed (1,007 links,
388 unique, 863 OK, 144 excluded, zero errors). Bundle-local checking included
all untracked Markdown files: 25 relative links, no missing targets or trailing
whitespace. `git diff --check` passed. These checks validate documentation only;
no production tests or full build were required or run for this documentation task.

## Network carrier amendment — 2026-09-22

Inspected live masks configuration, AddressIpValueProvider,
AddressUrlValueProvider, NetworkAddressClassifier, IndicatorFeatures and
RegexIndicatorExtractor. Recorded URL/domain inclusion, independent-carrier rows,
configurable structural gates/shape validation and future correlation boundaries.
Scheme-less/decorated-address routing remains a proposal awaiting clarification.
`make docs` passed: 1,017 links, 390 unique, 872 OK, 145 excluded, zero errors;
`git diff --check` passed. No production changes or feature tests were performed.


## Lifecycle restart decision

Owner accepted recreation after expiry from delayed previously uncommitted input.
Documented the distinction from committed-operation replay and from source threat
freshness. This is requirements evidence; no production behavior was changed.

## P0 architecture contract — 2026-09-22

Owner resolved Q-03 in favor of both file-ledger and JDBC service-ledger daemon
support. ADR-0030 now assigns business precedence to one dataframe-owned monotonic
registration sequence while service/file journals retain transport recovery state.
It also fixes the local producer handoff, pre-hash private claim, replacement
detection, terminal handshake, oneshot unresolved-state and coordinated-restore
contracts. The aggregate preset remains disabled and Q-04/Q-07 rollout choices
remain open. Implementation probes and executable recovery evidence belong to P2;
this checkpoint records the approved boundary, not runtime qualification.

## P1 configurable policy foundation — 2026-09-22

Commit `c215c103` adds strict `when-types`/`when` mapping conditions while
retaining the legacy `when-type` form, registry-backed predicates, relevant
semantic fingerprints and pure application policy types for occurrence selection
and latest-registered field updates. Focused mapping/configuration/policy tests
passed. No aggregate preset or runtime wiring was enabled.

## P2 durable admission foundation — 2026-09-23

Commit `f27740e6` adds dataframe and service schema v10, a single dataframe-owned
registration sequence, JDBC and file-backed admission journals, managed-import
references, oneshot ordering decoration, pre-hash document claim/seal handling,
terminal handshakes and exact retention guarded by live references/provenance.
Application contracts remain framework-free; SQL/filesystem ownership stays in
adapters. The published concurrency library API and artifact were unchanged.

Focused evidence at implementation time:

- `ObservationAdmissionServiceTest`: 5/5 after commit `272fbecc`, including a
  crash after registration but before journal linking and dry-run no-side-effect.
- `FileSystemSourceLifecycleIT`: 9/9 after `272fbecc`, including restart after
  token rename, open-writer mutation detection and file-journal restart.
- `JdbcObservationRegistrationStoreIT`: 2/2, covering concurrent global order,
  idempotency, namespace mismatch, missing recovery, overflow, terminal
  references and provenance-protected retention.
- P2 implementation-time `make pmd-analysis`, `make pmd-watchlist` and
  `make docs` passed. Final exact-HEAD release gates are reported separately
  after the checkpoint documentation commit.

This is infrastructure evidence, not end-to-end aggregate qualification. The
new admission collaborators are not wired into default bootstrap composition;
occurrence propagation, canonical `name` mutation, aggregate import/export and
activation remain P3–P6. Dataframe/service schema v10 also makes binary-only
downgrade unsafe; the coordinated restore procedure remains a P6 blocker.

## Final P0–P2 qualification — 2026-09-23

The final production and test tree is `fe0f0963`. The qualification compared it
with the isolated pre-feature baseline `e3f88ccf`; both complete reactor runs
passed. The evidence-ledger update itself changes no production, build or test
source.

| Check | P0–P2 result | Baseline / delta |
|---|---|---|
| `make verify` | All 25 reactor projects succeeded; test lifecycle reports 198 fast, 67 integration and 5 external suites, with 260 deterministic-offline suites | Baseline: 196 fast, 66 integration, 5 external and 257 deterministic-offline; P0–P2 add 2 fast, 1 integration and 3 deterministic-offline suites |
| Aggregate JaCoCo | 21,081/23,540 lines (89.55%); 6,930/8,659 branches (80.03%) | Covered code grew by 968 lines and 415 branches; total measured code grew by 1,150 lines and 536 branches. Percentage changed by -0.27 line points and -0.17 branch points while retaining the fixed floors |
| SpotBugs | 116 accepted, 0 visible | Unchanged count and baseline; no new accepted selector or suppression |
| CPD | 21/21 duplication groups | Unchanged count. Changed-range review found no new feature duplication group; four intersections are pre-existing `IocProperties`/import-catalog ranges shifted by the new nested records |
| `make pmd-analysis` | 0 blocking, 21/21 advisory | Unchanged ratchet. New admission/mapping/storage types add no finding; findings in touched legacy orchestration files remain existing reviewed advisories |
| `make pmd-watchlist` | 29 advisory findings | No finding intersects the new admission, policy, journal or JDBC registration classes |
| `make security-scan` | 129 dependency entries, 0 vulnerabilities | Offline NVD-backed scan passed; Sonatype OSS Index was unavailable without credentials and is not claimed as live external evidence |
| `make docs` | 1,017 links, 390 unique, 872 OK, 145 excluded, 0 errors | No broken documentation link |

Focused final application evidence includes 14/14
`ObservationAdmissionServiceTest` cases. They cover idempotent allocation,
crash-after-registration recovery, file/JDBC reference handshakes, conflicting
orders/outcomes, compare-and-set recovery, retention guards, dry-run behavior
and oneshot failure precedence. Provisioned external suites were not executed;
the five external suite shells remain discoverable and do not substitute for
stand qualification.

## P3 occurrence-preserving preparation — 2026-09-24

Commit `008b2b5d` carries attributed occurrences through batch deduplication and
rejoins one materialized classification decision to each occurrence. The CSV
adapter applies whole-row last-nonempty selection only for artifacts that opt in;
legacy policies continue to map one deduplicated indicator. Marker candidates
are resolved by start, longest span and configured pattern order so an embedded
short marker cannot steal attribution.

Commit `783d6b69` adds strict identity validation for occurrence/ordered-field
policies and matches Word-export NBSP forms without changing source offsets or
breaking literal pattern engines. Focused marker, configuration and CSV-preparer
tests passed. The aggregate preset remains absent, so this evidence qualifies the
mechanism and requested shared marker recognition, not product output.

## P4 ordered canonical mutation — 2026-09-24

Commit `facee3d0` applies the framework-free latest-registered value policy in
both lifecycle and disabled-lifecycle JDBC paths. Dataframe schema v11 persists
receipt field positions, public-update/metadata-only outcomes and archived field
origins. One SQLite transaction owns public value, origin, revision, projection
generation, commit marker and receipt publication. Registration retention now
also respects archived origins and canonical observation receipts.

Focused implementation evidence:

- application module test cohort passed, including public-change propagation;
- `IngestionServiceTest`: 34 passed, including receipt replay of a name-only
  public update into mutable projection and change publication;
- `JdbcCanonicalLifecycleWriterIT`: 15 passed, including deterministic
  out-of-order workers, same-value origin advancement, blank preservation and
  injected rollback of row/origin/receipt;
- `JdbcArtifactRepositoriesIT`: 10 passed, including compatibility-mode ordered
  updates and metadata-only revision behavior;
- complete JDBC adapter integration cohort: 227 tests, zero failures/errors and
  one property-gated load-profile skip;
- `CsvArtifactPreparerTest`: 4 passed; `IocPropertiesTest`: 8 passed.

The significant implementation discovery was retention ownership: a terminal
registration referenced only by lifecycle history or a committed canonical
observation must remain available until those facts expire. Schema v11 archives
field origin with lifecycle history, and cleanup tests prove the registration is
released only after the final authoritative reference is removed.

SpotBugs review removed a dead replay counter and rewrote constant field-origin
statements so the analyzer can see their fixed SQL shape. The remaining P4 SQL
findings are reviewed false positives: runtime identifiers come only from the
validated immutable artifact catalog or mapped schema columns, pass the adapter's
identifier grammar and quoting boundary, and all data values stay bound. The
exact baseline moves two existing selectors into the extracted compatibility
writer, refreshes five unchanged finding anchors and adds four net selectors for
new ordered-field read/update/archive/retention statements. The resulting raw
set is 120 accepted findings with zero proposal delta and zero visible findings
after filtering.

## P0-P4 final qualification — 2026-09-24

The implementation checkpoint through commit `1ac37414` passed the complete
deterministic offline release gate. A PMD-discovered complexity increase in
`WriteArtifactsStage` was removed by separating orchestration, canonical write
and projection responsibilities; the policy ratchet returned to its existing
count without suppression or baseline growth.

- `make verify`: passed; 19 production coverage groups and 18 required local
  reports were present. Aggregate coverage was 21,548/24,039 lines (89.64%) and
  7,063/8,827 branches (80.02%). The deterministic lifecycle contained 198 fast,
  67 integration and 5 external suite shells; the 260 deterministic-offline
  suites passed. The one JDBC load-profile case remained property-gated.
- SpotBugs: 120 reviewed raw findings, zero visible findings. CPD: 21/21
  reviewed duplication groups.
- `make pmd-analysis`: passed with zero blocking and 21/21 advisory findings.
  `make pmd-watchlist`: passed with 29 advisory findings; none touched a P3-P4
  changed file.
- `make security-scan`: passed against the existing offline Dependency-Check
  database; 129 dependencies were analyzed and zero vulnerabilities reported.
  This is deterministic offline evidence, not a live advisory refresh.
- `make docs`: passed with 1,017 links inspected, 390 unique, 872 successful,
  145 excluded and zero errors.

This qualifies the P0-P4 shared mechanisms. It does not qualify IOC aggregate
wire output, managed import, immutable export/delivery or production activation;
those remain P5-P7 scope.
