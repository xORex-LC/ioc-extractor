---
title: "DATA-AGGREGATE-01 — Evidence ledger"
version: "0.3.0"
status: "P0-P7 qualification complete"
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

## P5 aggregate artifact and import/export integration — 2026-09-25

Commit `72f4daa1` activates the five-column `ioc_aggregate` sink and the separate
`ioc-aggregate` immutable export profile in both classpath and packaging
configuration. The artifact starts empty, receives no historical backfill and
selects the opt-in occurrence/name policy without changing the four existing
artifact policies.

Commit `202fd03c` adds the explicit target-only managed-import contract. Its
compiled policy validates exactly one IP, URL, clean domain or hash carrier,
binds `name` through `source.label`, applies last-nonempty whole-record reduction
by normalized four-carrier key and fingerprints the effective mapping/policy for
sealed-stage recovery. Existing import contracts retain their duplicate rules.

Focused implementation evidence includes:

- golden pipeline output for all four carrier shapes with exact five-column
  order and NULL representation;
- independent immutable-export consumer bytes for
  `IOC_aggregate_generated.csv`;
- processed-import source binding, structural carrier validators, compiler
  rejection paths, normalized duplicate reduction and private workspace tests;
- existing artifact/profile configuration tests proving the new policy is
  selected only by `ioc_aggregate`.

These checks establish the P5 product path. Its deterministic aggregate
coverage and analyzer evidence is recorded in the combined checkpoint below;
representative performance evidence remains part of P7.

## P6 ordered-intake transition and operations — 2026-09-25

Commit `86bb0373` completes runtime admission for daemon document processing and
managed import. File and JDBC document journals recover the same dataframe-owned
registration order; import reservation is proven by service schema v11 before
canonical promotion. Startup blocks legacy unranked work instead of inventing an
order. Registration status, health and bounded retention expose unresolved
authority and preserve every live document, import, receipt and provenance
reference.

The same commit bounds upgrade compatibility: an exact v0.2 four-artifact
identity overlay receives the shipped aggregate identity only when the aggregate
sink is present, while partial or modified overlays remain strict. The operator
guide records coordinated backup/restore of configuration, both databases and
service-owned files; binary-only downgrade and schema stripping are unsupported.

Focused implementation evidence includes:

- `ObservationAdmissionServiceTest`: 16 passed, covering document/import
  references, recovery, terminal state transitions and retention boundaries;
- full JDBC adapter integration cohort: 233 tests, zero failures/errors and one
  property-gated load-profile skip;
- `JdbcCanonicalImportWriterContractIT`: 18 passed, including ordered import
  commit/replay behavior;
- `FileSystemSourceLifecycleIT`: 14 passed, including durable file-journal
  recovery and success/rejection/quarantine terminal reconciliation;
- ordered daemon retry, observation retention scheduler lifecycle, missing
  ingest-receipt recovery and strict import-validator failure paths.

## P5–P6 deterministic quality checkpoint — 2026-09-26

Follow-up commits `0ecacac0` through `6f1d9b4d` close runtime-contract,
recovery, validation, coverage and analyzer findings found during complete-gate
qualification. The final production refactor extracted one shared terminal
failure decision from the synchronous and asynchronous ingest paths; semantic
CPD review confirmed that this removed one real duplication group without
changing analyzer scope.

Final local deterministic evidence:

- `make verify`: all 25 reactor projects pass; aggregate JaCoCo is at least
  22,152/24,663 lines (89.82%) and 7,337/9,134 branches (80.33%). Repeated full
  runs observed a two-line execution range up to 22,154/24,663 (89.83%) without
  changing any ratchet result; domain remains 100%/100%, application remains
  above its 85%/90% floors;
- SpotBugs: 120 reviewed findings accepted by exact identity, zero visible;
- CPD: 24 reviewed duplication groups against the reduced 24-group ratchet;
- `make pmd-analysis`: zero blocking findings and 22/22 reviewed advisory
  findings; `make pmd-watchlist`: 30 advisory findings. The new
  `CloseResource` watchlist item is reviewed as the long-lived Spring
  `SmartLifecycle` scheduler's explicit shutdown/await/forced-shutdown owner,
  not a leaked per-call resource;
- `make security-scan`: 129 dependencies analyzed with zero unsuppressed and
  two narrowly suppressed findings. Both are reviewed false-positive CPE
  matches from PDFBox Examples against the distinct JBIG2 ImageIO artifact,
  bounded by the tracked 2027-01-31 review deadline. This is offline evidence
  from the existing local Dependency-Check cache, last checked on 2026-07-19;
  no live NVD refresh is claimed;
- documentation/link validation passes for the published and release-worknote
  changes.

P7 retains the representative duplicate-heavy memory/writer-latency comparison,
release publication and provisioned stand qualification. Offline skips and the
stale local vulnerability cache are not presented as live evidence.

## P7 stand, load and release qualification — 2026-09-26

The disposable stand root was `/srv/ioc-extractor`; retained run material is
under `var/p7-evidence-20260926T061956Z`. The candidate was exercised in
isolated stand workspaces because the installed systemd service requires
privileged restart. Its active release remained the prior
`3c02ba5c126c-r030-data-fresh` deployment. No claim is made that the systemd
unit was switched to the candidate.

### Upgrade, rollback and runtime paths

- A copy of the deployed v9 service/dataframe pair migrated to v11 with all
  pre-existing counts preserved and an empty aggregate. This confirms the
  no-backfill contract.
- The previous binary rejected a v11 dataframe with the typed
  `STORAGE.MIGRATION_DOWNGRADE` diagnostic (`fromVersion=11`, `toVersion=9`).
  Restoring the paired v9 databases and configuration allowed the same binary
  to start healthy, which qualifies coordinated rollback without suggesting a
  binary-only downgrade.
- File-ledger daemon intake wrote four separate rows from one BIB-labelled
  document: IPv4, full URL, FQDN and MD5. After restart, a later BIB-labelled
  delivery of the same IPv4 retained four rows and changed only that row's name.
- JDBC-ledger daemon intake reached `COMPLETED`/`SOURCE_ARCHIVED`; observation,
  run and projection state converged and no file ledger was created.
- Managed aggregate import reduced five accepted input records to four canonical
  rows: duplicate IP used the last nonempty name, URL/FQDN were normalized to
  lower case and MD5 to upper case. The terminal report and ECS event recorded
  four public mutations plus `IMPORT.DUPLICATE_IGNORED`. A two-carrier row was
  rejected with `IMPORT.NONEMPTY_CARDINALITY`, quarantined and made no canonical
  change. Both outcomes left actuator health `UP`.
- `make smoke SMOKE=all` passed the CLI, accumulated oneshot storage/export,
  daemon ingest/health and managed-import terminal/projection paths.

These scenarios also exposed four product defects before the final run: a
projection acknowledgement race, unbounded repeated diagnostics, transformation
of the public `NULL` token and concurrent lifecycle-clock writer admission.
Commits `b1c561bb`, `3147130c`, `8857b12e` and `de548814` fix them with focused
regression coverage. Candidate-file directory/symlink rejection is fixed and
covered by `e0e68997`; `3825523a` realigns the unchanged reviewed SpotBugs
identities after that source change.

### Representative load

The reproducible `tools/dev/ioc-aggregate-load.sh` profile uses a deterministic
seed and records elapsed time, write-stage duration, peak resident memory,
database size, aggregate cardinality, query plans and complete logs.

For 100,000 inputs at 95% duplicates, the successful prior four-artifact
baseline and aggregate candidate recorded:

| Metric | Baseline | Candidate | Candidate/baseline |
|---|---:|---:|---:|
| elapsed | 14,066 ms | 14,067 ms | 1.000 |
| `WRITE_ARTIFACTS` | 4,157,524,625 ns | 10,151,187,898 ns | 2.441 |
| peak RSS | 530,180 KiB | 599,724 KiB | 1.131 |
| dataframe DB | 12,296,192 bytes | 22,097,920 bytes | 1.797 |
| aggregate rows | 0 | 5,067 | n/a |

The overall elapsed threshold remains below 2.0 despite the expected additional
artifact write. The separate 250,000-input, 98%-duplicate candidate completed in
16,083 ms with an 8,763,274,584 ns write stage, 5,051 aggregate rows and
720,876 KiB peak RSS. This is below the service's 768 MiB `MemoryHigh` by
65,556 KiB (about 64 MiB), so the result passes while documenting limited
headroom for that deliberately heavy profile. Expected indexes were selected;
projection, storage and field-origin counts agreed and no error/fatal event was
emitted. Duplicate diagnostics were summarized after the configured delivery
budget instead of flooding logs.

Two attempts to rerun the old baseline immediately before the exact candidate
stopped with the old `LifecycleClockUnsafeException`: the system clock was
about 2 seconds behind the durable lifecycle high-water during concurrent
projection work. NTP was synchronized. This repeatable negative result is kept
as evidence for the shared-writer fix rather than treated as a performance
sample; the candidate completed the same environment without that error.

### Exact implementation gate and residual observation

At implementation HEAD `3825523a`, `make verify` passed all 25 reactor projects.
The test lifecycle recorded 200 fast, 67 integration, five property-gated
external and 262 deterministic-offline tests. Aggregate JaCoCo was
22,172/24,680 lines (89.84%) and 7,343/9,138 branches (80.36%); domain remained
100% and application remained above its 85%/90% floors. SpotBugs had 120 exact
accepted identities and zero visible findings; CPD held at 24/24 reviewed
groups. `make pmd-analysis` passed with zero blocking and 22/22 advisory items;
the separately reviewed watchlist contained 30 advisory items.

The offline Dependency-Check scan analyzed 129 dependencies and reported zero
vulnerabilities after the two existing narrow false-positive suppressions. It
did not refresh the NVD database or provide live external security evidence.

One internal telemetry seam remains: service schema v9 contains nullable
`import_delivery.public_mutations`, but the delivery transition does not copy
the canonical commit count into that column. Canonical dataframe `import_commit`,
the terminal JSON report and ECS completion event all retained the correct
value, and recovery reads the canonical receipt. The nullable service column is
not an operator/status authority, so this does not weaken idempotency, recovery
or the P7 result; its NULL value is recorded here to prevent it being mistaken
for missing canonical evidence.

## Live systemd activation — 2026-09-26

The privileged deployment transaction activated release
`71296e2e095d-20260926T110020Z` from commit
`71296e2e095d008a7ae89193b334aaaa042466f2`. The active jar SHA-256 is
`25eea5947fa87a4761b579d66d3cb2b21c45a977afcdfea42844b2380996525e`.
The deployment validated the existing effective configuration, stopped the
service, captured the paired database and systemd-unit backup, switched the
release, started it and passed the health gate. The rollback point consists of
`71296e2e095d-20260926T110020Z-db.tar` and its matching `-unit.service` sidecar.

The operator then applied the reviewed production template through `ioc-config`.
The resulting `application.yml`, reviewed candidate and generated
`application.yml.new` have identical SHA-256
`6d2460ef1ec1d6a51666559504ce31f35f9585bf9435335c0ddb05528ea76fdd`.
The helper retained the previous configuration as
`application.yml.backup-20260926T111325Z-806574` and reported the restarted
service `UP`.

Live acceptance against the root-owned systemd service confirmed:

- `/actuator/info` reports the exact commit and build time; overall health,
  artifact storage, observation registration, ingestion, lifecycle and export
  are `UP`;
- service and dataframe stores are both schema v11 with foreign keys enabled,
  WAL mode and `quickCheck=ok`; lifecycle admission is active and the clock is
  `SAFE` with zero backward skew/clamp age;
- startup memory was about 263 MiB with a peak around 269 MiB, below the 768 MiB
  high and 1 GiB hard limits;
- an atomically published HTML document labelled `БИБ-990001` created exactly
  four aggregate lifecycles: bare IPv4, full URL, clean FQDN and uppercase MD5.
  All four names and field origins used dataframe admission order 1;
- a second document labelled `БИБ-990002` containing only the same IPv4 reached
  the archive and updated that existing lifecycle in about five seconds. The
  aggregate remained four rows; only the IP name and origin changed to order 2.
  URL, FQDN and hash retained `БИБ-990001` and order 1;
- legacy `ip_list`, `masks` and `hashes` retained their existing keep-first
  source behavior. Both ingest runs completed across all five artifacts; both
  observation registrations are terminal and no document-admission work is
  unresolved;
- the aggregate immutable profile completed a new slice with revision lag zero.
  The active-process ECS log contained INFO events only: no WARN, ERROR or FATAL
  outcome was observed through completion.

Managed import remains intentionally disabled in the production template until
an operator configures a validated source. Its success, duplicate reduction,
invalid-carrier quarantine and canonical receipt behavior were qualified in the
isolated P7 stand scenario above; activation did not broaden that source policy.
