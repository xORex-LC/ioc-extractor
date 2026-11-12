# Known issues and technical debt

[Русская версия](ru/KNOWN-ISSUES.md)

This registry separates current defects and missing qualification from deliberate
extension options and completed work. IDs are stable and never reused. Capability
docs own mechanisms, ADRs own decisions, and release records own candidate-specific
results; this document owns the remaining problem, impact and completion condition.

Last reviewed **2026-11-07** against the `0.3.0-SNAPSHOT` source and retained
qualification evidence. A source review, passing focused test or historical load
result does not establish a fresh full release gate or qualify another deployment.

## How to use the registry

| State | Meaning |
|---|---|
| `open` | A demonstrated defect or accepted implementation task remains |
| `partial` | Part is implemented; the stated remainder is still open |
| `qualification` | Implementation exists, but the named target/acceptance evidence is missing |
| `deferred` | Deliberate extension or improvement; activate only on its stated trigger |
| `closed` | The original scope has implementation and supporting evidence; later limits stay separate |
| `out-of-scope` | The proposal was explicitly excluded from the current product scope |

Owners below identify responsibilities, not new assignments or delivery dates.
Legacy effort estimates are not a current schedule. Do not close an item solely
because an unrelated gate passes or an interface exists. On closure, keep its ID
and move the outcome and evidence to the archive. Link to an item with its lowercase
ID anchor, for example `KNOWN-ISSUES.md#ops-11`.

Address possible lost/skipped input and inconsistent activation first
([OPS-11](#ops-11), [OPS-12](#ops-12), [OPS-13](#ops-13), [OPS-14](#ops-14), [OPS-15](#ops-15)),
alongside the security disposition in [SRC-3](#src-3). Then address retry/status
failures and outstanding resource/deployment qualification. New enrichment,
formats and tooling options do not replace this operational work.

## Ingestion and canonical-write defects

<a id="1-демон--ингест-ing"></a>
<a id="ing-11"></a>
### ING-11 — Resume after a partial ingest run

**Partial · owner: ingestion/run-ledger.** Retries retain the document admission,
observation/rank and backoff, but preparation can create another ingest run.
After canonical commit, projection/archive failure can leave an earlier run
`DB_COMMITTED` while source work is still claimed. Fixed-lifecycle receipts may
avoid parsing and replay accepted rows; without a reusable receipt the retry
extracts again. This is not a complete resume protocol across admission, source
ledger and run-ledger; repeated work and abandoned partial runs remain possible.

Close with a designed checkpoint/resume contract that resumes only outstanding
projection/archive/finalization after a proven commit, coordinates all three
ledgers, and tests failures/restarts with lifecycle enabled/disabled and receipts
present/expired. Do not equate receipt replay with projection-only resume.
See [ingestion](dev/ingestion.md#receipts-and-retry) and
[IngestionService](../core/ioc-application/src/main/java/com/iocextractor/application/ingest/IngestionService.java).

<a id="ing-13"></a>
### ING-13 — Physical fate of failures before a trustworthy content key

**Partial · owner: document admission/source lifecycle.** Retry exhaustion and
terminal-log suppression exist. Pre-reservation failure can still leave input in
the inbox; ledger-only rejection can persist `unknown` paths. A reserved pre-hash
failure instead retains its owned token, blocks later promotion and makes capacity
health DOWN. There is no supported general clear/requeue command. These are
different failure windows, not one endlessly repeated poll log.

Investigate the original file/token and use coordinated recovery/restore; do not
clear SQLite rows or delete private evidence by hand. Close with explicit,
idempotent quarantine/disposition for each pre-claim/pre-hash window, real source
evidence and a supported recovery interface. See [daemon operations](guides/daemon-operations.md)
and [ingestion](dev/ingestion.md#receipts-and-retry).

<a id="2-обогащение-вывода-out"></a>
<a id="out-3"></a>
### OUT-3 — Legacy mapped-key collisions with fixed lifecycle

**Open · owner: processing/canonical writer.** Legacy KEEP_FIRST preparation retains
accepted mapped rows. With lifecycle active, duplicate final keys can produce
`SINK.WRITE_FAILED` before ID reservation/commit: for example with pipeline dedup
disabled, or distinct retained IOC values mapping to one key. Without lifecycle,
canonical uniqueness/provenance use the existing keep-first path.

Close by defining lifecycle confirmation of duplicates or a validated configuration
restriction. Collapsing rows early must not silently change reservation,
occurrences or provenance. The regression
`GoldenPipelineIT.originalRouteMappedCollisionsPreserveReservationAndProvenanceInBothWritePaths`
pins the current limitation. See [processing](dev/processing.md).

<a id="ops-14"></a>
### OPS-14 — Whitespace-only imported ordered field bypasses its guard

**Open · owner: managed import/canonical field mutation.** In
`JdbcCanonicalImportWriter`, a blank VALUE is not an ordered candidate, but generic
merge can still write it. The packaged local-daemon reproduction with
`ioc-aggregate-v1` replaced `Feed-A` with three spaces while keeping four carrier
rows; field-origin rank did not advance. The intended keep-existing behavior and
ordered-field authority are therefore bypassed in this branch.

For an absent name, supply an empty CSV cell/NULL, not padding; preview is not a
merge-outcome guarantee. Close by applying one blank policy before generic mutation
and testing nonblank/blank/NULL, both registration orders, new/active lifecycles
and real aggregate import. See [aggregate operations](guides/ioc-aggregate.md)
and [import writer](../adapters/adapter-store-jdbc/src/main/java/com/iocextractor/adapter/out/store/jdbc/JdbcCanonicalImportWriter.java).

## Sync, status and deployment defects

<a id="7-экспорт--упаковка--ops-exp--ops"></a>
<a id="ops-10"></a>
### OPS-10 — Import status fails on a NULL retry deadline

**Open · owner: JDBC status/bootstrap health.** `JdbcImportStatusReader` reads SQL
NULL with `getObject("next_attempt_at_ms", Long.class)`, which the selected SQLite
driver rejects. A real CLI reproduction after replay into `SNAPSHOT_PINNED` failed
with `Bad value for type Long`. `DataframeImportHealthIndicator` shares this reader;
this status failure does not establish that canonical commit failed.

Close with a nullable read and regressions for empty queue, NULL/non-NULL head
and actual CLI/health on nonterminal work. Do not patch database timestamps to
hide the error. See [reader](../adapters/adapter-store-jdbc/src/main/java/com/iocextractor/adapter/out/store/jdbc/JdbcImportStatusReader.java)
and [import operations](guides/dataframe-import.md).

<a id="ops-11"></a>
### OPS-11 — Fetch identity omits source/endpoint namespace

**Open · owner: fetch identity/ledger.** `RemoteObjectIdentity`, process-local
in-flight tracking and the JDBC primary key use only `(path,size,mtime)`.
Two endpoints with the same tuple but different content reproduced a silent skip
of the second endpoint without GET. Polling/reconcile cannot repair a wrong identity.

Use non-overlapping remote paths/names until fixed; this is a constraint, not
cross-process fencing. Close with an explicit namespace contract, compatible
ledger/in-flight migration, and two-endpoint equal-tuple/restart regressions.
See [identity](../core/ioc-application/src/main/java/com/iocextractor/application/sync/RemoteObjectIdentity.java)
and [JDBC ledger](../adapters/adapter-store-jdbc/src/main/java/com/iocextractor/adapter/out/store/jdbc/JdbcRemoteFetchLedger.java).

<a id="ops-12"></a>
### OPS-12 — Fetch landing can overwrite a concurrent input

**Open · owner: fetch/local landing.** `finalPathFor` checks absence before download;
`Files.move(ATOMIC_MOVE)` does not guarantee no-replace if a target appears later.
On Linux, two synchronized endpoint downloads with different identities produced
two FETCHED records but one inbox file. This is a demonstrated lost-input window.

Use distinct final names and coordinate all inbox writers; a separate CLI does
not share the daemon lane. Close with atomic ownership/no-replace landing,
deterministic endpoint/external-writer regressions and preserved crash-window
idempotency. See [fetch service](../core/ioc-application/src/main/java/com/iocextractor/application/sync/RemoteFetchService.java)
and [remote operations](guides/remote-storage-sync.md).

<a id="ops-13"></a>
### OPS-13 — Failed deployment preflight leaves new host files

**Open · owner: local activation/packaging.** `deploy-local-root.sh` refreshes the
systemd unit and `bin/ioc{,-config}` before semantic preflight. Explicit `die` exits
bypass the ERR restoration trap. The running process, `current` and databases
remain old, but a later restart/CLI can combine new host files with the old JAR.
The reproduction used the actual source segment with stubbed privileges/systemd.

Before restart compare the unit with `backups/<failed-release-id>-unit.service`,
restore matching unit/helpers from the rollback point and reload systemd. Close
with one failure path or staging before preflight, plus rejection/restart tests
checking unit/helpers/current/databases. See [activation script](../packaging/deploy-local-root.sh)
and [deployment](guides/deployment.md).

<a id="ops-15"></a>
### OPS-15 — Unchecked migration failure can commit partial DDL

**Open · owner: SQLite schema migration.** `SqliteUserVersionSchemaMigrator.apply`
rolls back `SQLException`, but its `finally` restores auto-commit without an
unconditional rollback guard. On a normally auto-committing connection, an
unchecked failure or Error between DDL statements can therefore commit earlier
statements while `user_version` remains unchanged. A 2026-11-12 fault-injection
probe on file-backed SQLite threw `AssertionError` before the second CREATE:
the first table remained durable at version zero. This affects both migration
catalogs; canonical writers' stronger cleanup contract does not cover it.

After abnormal migration failure, preserve and inspect schema/version evidence
and restore a coordinated backup before retrying; do not lower `user_version`
or assume the failed version left no changes. Close with rollback-before-restore
for every unsuccessful pre-commit exit, original/suppressed failure preservation
and regressions for SQL, unchecked and Error paths. A post-commit observer failure
must remain distinguishable from rollback. See the
[migrator](../adapters/adapter-store-jdbc/src/main/java/com/iocextractor/adapter/out/store/jdbc/SqliteUserVersionSchemaMigrator.java)
and [storage adapter](../adapters/adapter-store-jdbc/README.md).

## Resource, security and target qualification

<a id="8-source-parsing--dependency-surface-src"></a>
<a id="src-3"></a>
### SRC-3 — Runtime advisory matches need security disposition

**Open · owner: dependency/security review and affected adapters.** The retained
application JAR contains Jackson core/databind 2.21.5 and 3.1.5, Junrar 7.6.0,
Guava 33.3.1-jre and jsoup 1.23.1. The 2026-11-07 OSV refresh of its 171 external
coordinates returned 11 advisory IDs across these seven coordinates. This is an
affected-version screen, not proof that every vulnerable API is reachable in IOC.
Examples: [Guava](https://github.com/advisories/GHSA-xxph-c9ww-hj94),
[jsoup](https://github.com/advisories/GHSA-65r4-943x-97jj),
[Jackson](https://github.com/advisories/GHSA-cxp5-3px4-pw24).

The [dated affected-version matrix](../.github/release-evidence/0.3.0.md#src-3-dated-affected-version-screen)
retains all eleven advisory IDs, seven packaged coordinates and the screened JAR identity.

Close with owner-reviewed reachability/disposition for each match or compatible
updates, regression/consumer checks and a fresh release security gate. Deployment
health is not a security result; an indefinite suppression is not disposition.
See [security policy](SECURITY-ENGINEERING.md) and [deployment dependencies](guides/deployment.md).

<a id="src-2"></a>
### SRC-2 — Parser budgets do not cover every supported input path

**Partial · owner: source parsing/bootstrap security.** The old unbounded-buffer
description is obsolete: production document processing uses disk-backed cursors,
streaming HTML/DOCX, bounded workspaces and daemon source admission. HTML repair
regions are bounded too. However, there is no uniform operator contract for
parser decompression ratio/depth and wall-clock cancellation across all Tika
formats and oneshot/daemon paths. The String-returning convenience API still
materializes text; managed CSV import limits are a separate contract.

Close by defining input/parser/time budgets, cancellation and typed failure
outcomes for the claimed paths, then testing malicious/oversized formats without
partial canonical writes. Do not generalize HTML/DOCX bounds to every auto-detected
format. See [processing](dev/processing.md#preparation-and-bounded-data),
[capacity](dev/native-capacity.md) and [threat model](THREAT-MODEL.md).

<a id="ing-15"></a>
### ING-15 — Large-document end-to-end resource acceptance

**Partial · owner: source/ingestion/private reducer/canonical storage.** Streaming
removed the historical million-IOC source OOM for HTML/DOCX; fatal dispatcher
failure is visible and recoverable. On the old 512-MiB profile, million-IOC runs
then exhausted the private SQLite quota before canonical commit; 100k runs
exceeded time/writer budgets. Later million-DOCX/100-document Native runs completed
on an explicitly expanded profile (2-GiB heap, 8-GiB workspace, 6-GiB cgroup).

Those are separate measured profiles, not universal acceptance of old defaults
or all document shapes. Close with agreed complete-cycle resource/SLO envelopes
and their workload/concurrency qualification. Historical failures stay failures.
See [capacity measurements](dev/native-capacity.md) and [sizing](guides/bulk-resource-sizing.md).

<a id="ing-16"></a>
### ING-16 — UTC versus durable high-water discrepancy

**Partial · owner: service coordination/canonical clock/operations.** Admission,
import retry, terminalization and retention progress now clamp timestamps to the
stored `updatedAt`; backward UTC no longer breaks those transitions. Canonical
safe-clock checks remain independent and still reject unsafe skew. The cause of
the historical raw-UTC/high-water discrepancy is not established; logs do not
prove a host-clock adjustment. The original 2.061-s failure and later
2.073–2.363-s rejections remain historical failures.

Close the remaining investigation with clock/progress evidence and disposition,
and qualify any accepted clock policy under load. Do not relax canonical safety
merely because coordination was repaired. See [ingestion](dev/ingestion.md#receipts-and-retry)
and [lifecycle](dev/canonical-record-lifecycle.md).

<a id="out-4"></a>
### OUT-4 — Selected-processing resource budget remains unaccepted

**Qualification · owner: processing/application.** Historical selected-path
measurements preserved correctness but exceeded accepted allocation/heap budgets:
about 4.04× caller allocations for 100k/20 keys and about 1.255×/1.294× sampled heap
for 50%/0% duplicates against a 1.25× limit. A later URL→host/IP workload retained
occurrence-proportional mapping and about 3.67 GB caller allocations. These are
dated measurements, not a fresh comparison of every later cleanup.

Close with agreed workload/resource limits and measured improvement preserving
diagnostics, IDs, provenance and import participants, including concurrent
application SLOs. Passing build gates is insufficient.
See [processing](dev/processing.md) and [measurement history](dev/native-capacity.md).

<a id="ops-8"></a>
### OPS-8 — Managed SMB import needs per-target qualification

**Qualification · owner: SMB import/target deployment.** Historical P8 Samba results
do not establish the later two-identity ADR-0025 contract for every server family.
Each claimed target needs distinct producer/service identities, capability gate,
producer-denial, server no-replace rename/share modes, stable file ID, disconnect
recovery, exact terminal purge and `CHANGE_NOTIFY` evidence. Polling replaces lost
notifications, not ownership/retention checks.

Keep managed SMB import disabled until its configured source is qualified.
Close per claimed family with the full evidence; an offline skip or one family
cannot qualify another. This ID replaced an accidentally reused active OPS-7;
the historical OPS-7 fast path remains closed separately.
See [import operations](guides/dataframe-import.md) and [ADR 0025](ADR/0025-managed-import-smb-service-namespace-and-terminal-retention.md).

## Active library and developer-tooling work

<a id="9-developer-tooling--build-tool"></a>
<a id="tool-6"></a>
### TOOL-6 — Automated compatibility comparison of published APIs

**Open · owner: library API/Maven/CI.** Current consumers do not compare the entire
public/protected API with a released baseline; removing an unused method can pass
CI and break another service. The scope now includes all six admitted libraries,
not only concurrency/diagnostics. Activate before their next release, especially
when APIs change; behavioral review/tests remain separate from source/binary comparison.

Completion requires:

- Explicit published baseline, checked API surface, reproducible command and
  source/binary compatibility report for each admitted component.
- CI/release integration that fails on unexpected incompatibility or unavailable
  baseline; fetching it must not add required network to offline `make verify`.
- Regressions detecting removed/changed methods even when current consumers do
  not call them, and accepting a compatible internal change.
- Reviewed intentional breaks following [version policy](RELEASE-PROCESS.md#reusable-library-versions),
  including minor/migration notes in 0.x. Do not silently update a baseline to pass.
  POM/CI versions remain shared until TOOL-9; events retain exact family equality.

[japicmp](https://github.com/siom79/japicmp) is a candidate, not an adopted gate.
See [development](guides/libraries/development.md) and [publication](guides/library-publication.md).

<a id="tool-7"></a>
### TOOL-7 — Unexplained temporary GPG-key fixture failure

**Open · owner: publication tooling.** During E4 on 2026-10-25, the logging signing
fixture's `--quick-generate-key` returned 2 and its stderr was lost. The fixture
now retains safe stderr; separate generation and later full suites passed, but
the original cause is unknown. No automatic retry was introduced.

Close by capturing stderr/GnuPG version/conditions on reproduction, finding and
addressing the cause, and passing the complete suite without retries. A green
rerun alone is insufficient. See [offline tests](../tools/tests/library-publication-test.py)
and [retained release evidence](../.github/release-notes/ioc-events-spring-0.3.0-rc.1.md#packaged-consumers-and-retained-evidence).

<a id="tool-8"></a>
### TOOL-8 — Configuration Central consumer lacks protected CI requalification

**Qualification · owner: publication tooling.** `ioc-config-spring-boot:0.3.0-rc.1`
is published in both repositories. Run `40088095516` passed GitHub consumption,
but its Central routing failure was concealed by `tee`. Corrected local Central
consumption passed; routing/pipefail fixes are integrated. No later protected
recovery result is recorded; the Actions run-list review on 2026-11-07 found no
publication run newer than 2026-10-25.

Close with protected recovery using original validation `40087127800`, the same
tag, signed bytes and deployment `90e22519-418d-4b74-ac5a-cadcc3d2c559`, with no
rebuild/resign. Review actual logs, hashes and origins from both CI consumers.
Local success does not close it. See [component record](../.github/release-notes/ioc-config-spring-boot-0.3.0-rc.1.md)
and [consumer](../tools/fixtures/configuration-consumer/README.md).

<a id="tool-9"></a>
### TOOL-9 — Activate independent library versions in Maven and CI

**Open · owner: Maven/publication tooling.** [ADR 0048](ADR/0048-independent-library-versions-and-reuse.md)
accepts separate concurrency/diagnostics/logging/configuration versions and one
events core/adapter family version. Current POM dependencies and admission by
`release-X.Y.Z` still use the shared revision; a global `-Drevision` does not
establish independence.

Close all [activation criteria](RELEASE-PROCESS.md#activation-of-independent-versions):
separate version sources, product/library-only scenarios, immutable source/tag/
recovery identity, exact-version events family and service consumption without
local reactor substitution. Trigger: before the first independently versioned
release. A policy change alone is insufficient.

<a id="tool-10"></a>
### TOOL-10 — Lifecycle smoke/load waits for an obsolete terminal filename

**Open · owner: lifecycle developer tooling.** `lifecycle-smoke.sh` waits for
`*-initial.html`/`*-reappearance.html`; Native archives sealed input as
`.html.sealed`. The 2026-11-04 1,000-row smoke stopped at the first wait despite
an existing done file; later expiry/retention/non-reuse assertions did not run.
`make lifecycle-load` shares this harness.

Close with a terminal-delivery-aware wait that cannot falsely match another
file, a regression fixture and a complete isolated smoke without retries;
qualify load separately with its thresholds. Focused JUnit does not replace it.
See [harness](../tools/dev/lifecycle-smoke.sh) and [lifecycle operations](guides/canonical-record-lifecycle.md).

## Deferred improvements and extension options

These entries do not promise an implementation or a release date. A port, small
module or supported configuration knob is not itself a defect. Keep the inward
dependency rule and service-owned policies when activating a proposal.

<a id="3-экстракция--корпус-ext"></a>
<a id="4-наблюдаемость-obs"></a>
<a id="5-код-смелл-code"></a>
<a id="6-архитектура--модульность-arch"></a>

| ID | State and remaining scope | Trigger / completion | Owner and reference |
|---|---|---|---|
| <a id="ing-7"></a>ING-7 | `deferred`: eligible mutable projection refresh still reads and rewrites the full active artifact, O(N); immutable export has independent cadence | Measured projection cost warrants incremental/cache or generation policy; preserve snapshot/expiry/recovery consistency | Storage/projection; [export](dev/artifact-export.md) |
| <a id="ing-8"></a>ING-8 | `deferred`: HTTP use-case driving adapter; loopback actuator is not REST ingest/TAXII/BFF | A real API consumer; separate adapter, request correlation and authenticated non-loopback health/details | Application/web; [architecture](ARCHITECTURE.md) |
| <a id="out-1"></a>OUT-1 | `deferred`: no general enrichment providers; shipped reputation metadata defaults are NULL, but constants/import can populate configured fields | Consumer enrichment contract; public first/last-seen timestamps must not be confused with internal lifecycle validity | Processing/domain; [CSV contract](dev/csv-output-format.md) |
| <a id="out-2"></a>OUT-2 | `deferred`: CSV quoting is not spreadsheet formula neutralization; default source markers constrain dangerous prefixes, arbitrary future text may not | Broader marker/free-text policy; validate/reject or add explicit spreadsheet encoding without silently changing machine-feed values | Output/security; [threat model](THREAT-MODEL.md) |
| <a id="ext-1"></a>EXT-1 | `deferred`: IPv6/email types and their end-to-end contracts are absent; adding a regex is insufficient | A supported consumer needs them; define type, normalization, classification, carrier/export/import and tests | Domain/processing; [processing](dev/processing.md) |
| <a id="ext-2"></a>EXT-2 | `partial`: generic domain detection can emit `.onion`; exact v3 56-character base32 boundaries are not enforced | Adopt strict onion validation with positive/negative boundaries on both regex engines | Extraction/classification; [pattern contract](../bootstrap/ioc-app/src/test/java/com/iocextractor/bootstrap/PatternEngineConfigurationTest.java) |
| <a id="ext-3"></a>EXT-3 | `partial`: golden, boundary and engine-parity corpora exist; broad representative provider/non-PSL expectations are incomplete | Expand with independent expected outputs and explicit host/provider semantics; manual variant-1/3 examples are not a variant-2 oracle | Domain/fixtures; [testing](TESTING.md) |
| <a id="obs-4"></a>OBS-4 | `deferred`: durable diagnostic occurrence/report/quarantine contract, beyond synchronous best-effort delivery | Real audit/reprocess need; define identity, retention and whether report failure affects processing; do not derive durable IDs from MDC | Diagnostics/application; [observability](dev/observability.md) |
| <a id="obs-5"></a>OBS-5 | `deferred`: generated Elasticsearch mappings and rollover/reindex migration | First supported ES/data-stream consumer; install mappings from field metadata before indexing; a producer fix cannot repair old mappings | Observability/consumer; [logging](guides/libraries/logging-slf4j.md) |
| <a id="obs-6"></a>OBS-6 | `deferred`: invalid field type intentionally throws before log-level checking; fault-tolerant logging policy is undecided | A service needs isolation; define visible bounded failure without recursion/value disclosure. Strict current behavior is a supported contract | Logging/service integration; [LogEvent tests](../platform/platform-logging-slf4j/src/test/java/com/iocextractor/platform/logging/LogEventTest.java) |
| <a id="code-1"></a>CODE-1 | `partial`: convenience constructors were removed; extraction factory/production constructor still have a long dependency list | A meaningful cohesive composition boundary, not a parameter bundle merely to reduce argument count | Composition root; [modularization](MODULARIZATION.md) |
| <a id="code-5"></a>CODE-5 | `deferred`: no uniform explicit nullness contract/build analyzer; JDT automatic annotations differ by module classpath | Adopt one incremental contract/analyzer with explicit boundary nullability; preserve useful existing IDE diagnostics | Java/build; [JDT preferences](../tools/eclipse-jdt.prefs) |
| <a id="arch-1"></a>ARCH-1 | `deferred`: review platform module granularity only if dependency/release overhead is demonstrated; a tiny module is not a violation | Measured maintenance cost and boundary-preserving design; do not merge independent integration families merely by line count | Architecture/platform; [module map](MODULARIZATION.md) |
| <a id="arch-2"></a>ARCH-2 | `deferred`: `PatternEngine` remains an inward domain port, not a shared platform regex API | A genuinely generic external consumer; keep framework dependencies outward | Domain/platform; [architecture](ARCHITECTURE.md) |
| <a id="arch-3"></a>ARCH-3 | `deferred`: Spring Modulith/canvas is an evaluation option, not an installed seam | Concrete architecture-analysis need and a useful signal beyond current reactor/ArchUnit enforcement | Architecture/build; [testing](TESTING.md) |
| <a id="exp-1"></a>EXP-1 | `deferred`: STIX/OpenIOC export adapter absent; `stixType()` metadata is not a complete wire model | Real exchange consumer; define mapping, identity, version and independent consumer fixtures | Export/domain; [architecture](ARCHITECTURE.md) |
| <a id="exp-2"></a>EXP-2 | `deferred`: no `.deb` lifecycle; shell installer/deployment remain supported | Distribution requirement; define upgrade/config/permissions/rollback semantics and package tests | Packaging; [deployment](guides/deployment.md) |
| <a id="ops-2"></a>OPS-2 | `deferred`: no project-supplied OS logrotate policy; packaged daemon has Logback rolling files and journald | Host retention policy requires it; avoid competing rotation owners and qualify permissions/reopen behavior | Operations/packaging; [observability](dev/observability.md) |
| <a id="exp-3"></a>EXP-3 | `deferred`: active appliance delivery/API connectors beyond file-slice SMB publication | A concrete appliance; consumer-owned delivery ledger/retention view, AND-composed guards and separate driven adapter | Export/delivery; [ADR 0014](ADR/0014-event-driven-ingest-to-delivery.md) R3 |
| <a id="exp-4"></a>EXP-4 | `deferred`: DuckDB/Parquet analytics option, not an adopted engine | Columnar/analytics consumer plus measured value; keep SQLite canonical write authority | Read/export adapters; [storage](dev/storage.md) |
| <a id="exp-5"></a>EXP-5 | `deferred`: immutable export only executes `complete`; append/update modes require more than vocabulary | Durable watermark and explicit consumer contract; unsupported mode must fail before I/O | Export; [artifact export](dev/artifact-export.md) |
| <a id="ops-3"></a>OPS-3 | `partial`: generic sync lacks the full capability gate; managed import already has blocking per-source probes/health | Need for broader generic preflight; preserve operator-owned share/ACL and separate producer-denial qualification in OPS-8 | SMB/sync; [import](dev/dataframe-import.md) |
| <a id="ops-4"></a>OPS-4 | `deferred`: baseline event/reconcile/keyed coordination exists; durable outbox/broker and high/low-water hysteresis do not | Real interprocess delivery or measured pressure requiring hysteresis; events remain hints, ledgers/reconcile authority | Sync/events; [coordination](dev/event-coordination.md) |
| <a id="ops-5"></a>OPS-5 | `deferred`: fetch-history retention and targeted discovery | Bound historical ledger growth with reviewed idempotency retention; optimize listing only after detection-duration evidence | Fetch/storage; [sync](dev/sync.md) |
| <a id="ops-6"></a>OPS-6 | `deferred`: fetch is serial within an endpoint; cross-endpoint execution exists | Measured one-share batch SLO bottleneck; bounded sessions/admission/health and first address OPS-11/12. Parallel safety is not blanket-proven | SMB/concurrency; [sync](dev/sync.md) |
| <a id="src-1"></a>SRC-1 | `deferred`: Tika umbrella parser set exceeds the qualified HTML/PDF/DOCX/XLSX baseline | Measured size/security benefit justifies loss of best-effort formats; compare explicit modules and extraction quality | Source/packaging; [source reader](../adapters/adapter-source-tika/src/main/java/com/iocextractor/adapter/out/source/TikaSourceReader.java) |
| <a id="tool-1"></a>TOOL-1 | `deferred`: safe affected-test accelerator; existing taxonomy/module targets already work | Measured local-cycle cost; include staged/unstaged/untracked paths and upstream/downstream closure, fall back to full gate for unknown/shared changes | Build; [testing](TESTING.md) |
| <a id="tool-2"></a>TOOL-2 | `deferred`: deterministic Java formatter/fmt-check absent; SpotBugs/CPD/PMD gates already exist separately | Dedicated mechanical-change slice after formatter/diff review; introduce check only after clean baseline | Build/style; [build quality](dev/build-quality.md) |
| <a id="tool-3"></a>TOOL-3 | `partial`: seeded fixtures, isolated runtime, smoke and dedicated lifecycle/import/aggregate load tools exist; no single generic performance report contract | Need for comparable oneshot measurements: input/unique/committed counts, diagnostics, elapsed/memory and binary/config identity. Keep TOOL-10 failure and load acceptance separate | Developer tooling; [tools](../tools/dev/README.md) |
| <a id="tool-4"></a>TOOL-4 | `deferred`: external suites still require operator-provided fixtures; centralized capability/provisioning/evidence control is absent | Before another integration family or required external CI status; meet the acceptance contract below | Integration/test infrastructure; [external tests](TESTING.md#external-and-slow-evidence) |
| <a id="tool-5"></a>TOOL-5 | `deferred`: PIT pilot is intentionally domain-only; no reviewed adoption matrix for other production scopes | Dedicated test-quality work or a demonstrated critical assertion gap; meet the acceptance contract below | Test quality/module owners; [mutation pilot](TESTING.md#diagnostic-pilots) |

<a id="arch-5"></a>
### ARCH-5 — Review internal ETL contracts before external reuse

**Deferred · owner: ETL/platform architecture.** The internal runner and direct
`Pipeline` construction need a deliberate consumer-contract review before reuse
outside this application. The constructor does not validate payload-type chains;
custom failure policies inspect compacted retained details, rather than complete
history. Policy runs after stages: initial FATAL does not prevent the first stage,
and an empty chain has no checkpoint. Observer/cleanup failures can follow stage
effects, and `stageFailed` is not exhaustive. The runner owns no transaction,
rollback, retry, timeout or cancellation protocol.

Keep the kernel internal, use typed composition and keep effect/recovery ownership
in the application. Non-throwing observers and sinks avoid adding failures after
effects; they do not provide durable completion evidence. These limits are now
documented in Javadoc and the module README; that documentation is neither a
behavior fix nor library admission. This deferred review is not a release blocker.

Activate before external reuse/publication. Close with explicit supported contracts
and consumer regressions for direct type composition, compacted custom policy,
seed diagnostics/empty chains, callback/scope failures and resource ownership.
See the [module reference](../platform/platform-etl/README.md),
[Pipeline](../platform/platform-etl/src/main/java/com/iocextractor/platform/etl/Pipeline.java),
[runner](../platform/platform-etl/src/main/java/com/iocextractor/platform/etl/PipelineRunner.java)
and [observer](../platform/platform-etl/src/main/java/com/iocextractor/platform/etl/PipelineObserver.java).

### External-harness acceptance for TOOL-4

Require one suite registry with lifecycle, capabilities, expected counts, timeout,
owner, cadence and gate policy; a secret-free provisioned target descriptor;
readiness/capability checks; isolated namespace/lease; failure-safe verified cleanup
and bounded janitor. Credentials enter only through a secret boundary, not Maven
properties or retained reports. The verifier must prove expected equals executed,
reject silent provisioned skips and distinguish product/environment/capability/
cleanup/inconclusive outcomes. Evidence binds commit, suite/runner/provider
revision, redacted target, reports, diagnostics and cleanup. A stable Make/CI
facade must migrate SMB plus a second real integration family; abstractions alone
do not close it. Offline verify remains independent.

### Mutation-expansion acceptance for TOOL-5

Use the current production universe from the build configuration, not the obsolete
21-module count; TCK stays outside production mutation denominators. Start with
pure application/platform scopes; do not admit integration-heavy adapters/bootstrap
by default. Record
adopt/defer/reject per scope, critical behavior/test consumers and rationale.
Admitted packages/consumers must have reproducible commands, HTML/XML reports,
commit/tree identity and no silent overlap/gaps. Measure time, memory,
mutation/test-strength signal, NO_COVERAGE and survivor noise; give survivors an
owner/disposition. Adopt cadence/gates only after stable pilots and reviewed policy.
Decide differential analysis separately, including cache correctness and licensing;
do not turn the diagnostic pilot into an unconditional PR score.

## Closed and excluded items

<a id="архив-закрытых-долгов"></a>

Archive entries preserve the original scope; they do not claim that all neighboring
features or resource budgets are complete. Dated qualification remains dated.

| ID | Disposition | Implemented outcome and evidence |
|---|---|---|
| <a id="ing-1"></a>ING-1 | `closed` | Declarative done/failed retention and delete/archive ports; obsolete partition retention removed. [Retention](dev/ingestion.md) |
| <a id="ing-2"></a>ING-2 | `out-of-scope` | Tail/offset/rotation mode excluded for discrete documents. A future streaming source needs a new source-mode contract, not a dormant promise. [Processing](dev/processing.md) |
| <a id="ing-3"></a>ING-3 | `closed` | Daemon-only loopback actuator/health transport; REST use-case adapter remains ING-8. [Daemon operations](guides/daemon-operations.md) |
| <a id="ing-4"></a>ING-4 | `closed` | Service ledger plus canonical JDBC dataframe truth, per-artifact identity and mutable CSV projection; partition staging removed. [Storage](dev/storage.md) |
| <a id="ing-4a"></a>ING-4a | `closed` | Durable STARTED→DB_COMMITTED→PROJECTION_COMPLETED→COMPLETED checkpoints and run recovery; cross-ledger partial-run resume remains ING-11. [Ingestion](dev/ingestion.md) |
| <a id="ing-5"></a>ING-5 | `closed` | Separate aggregation trigger removed with partition staging; direct canonical ingest. [Ingestion](dev/ingestion.md) |
| <a id="ing-6"></a>ING-6 | `closed` | Partition-wrapper boundary removed; source identity belongs to application/adapters. [Architecture](ARCHITECTURE.md) |
| <a id="ing-9"></a>ING-9 | `closed` | Retention archive preserves relative target paths using baseDir instead of flattening leaves. [Retention tests](../core/ioc-application/src/test/java/com/iocextractor/application/maintenance/RetentionServiceTest.java) |
| <a id="ing-10"></a>ING-10 | `closed` | Recovery-before-poller startup, source-key guard and monotonic transitions; single daemon, not distributed fencing. Checkpoints f4f011e/c3a03e2/a44d10f/7ce5f8f; [ingestion](dev/ingestion.md) |
| <a id="ing-12"></a>ING-12 | `closed` | Bounded hash retry and typed terminal unreadable-source diagnostic; physical pre-claim fate remains ING-13. [Ingestion](dev/ingestion.md) |
| <a id="ing-14"></a>ING-14 | `closed` | Quiet-period discards re-enter the WatchService retry set; default full polling remains correctness path. [Filter integration test](../adapters/adapter-ingest/src/test/java/com/iocextractor/adapter/in/ingest/IngestFileListFilterIT.java) |
| <a id="ing-17"></a>ING-17 | `closed` | Equivalent terminal CAS accepts only unchanged authority/evidence and verifies registration even on finalized retry. Completion regressions and the recorded corrected 100-DOCX run support closure; failed load windows remain failed. [Completion tests](../core/ioc-application/src/test/java/com/iocextractor/application/ingest/admission/DocumentAdmissionCompletionTest.java), [history](dev/native-capacity.md) |
| <a id="ops-1"></a>OPS-1 | `closed` | Original multi-file/new-file verification is covered by recorded 100-DOCX intake; deterministic running-daemon/watch tests were freshly rechecked 2026-11-07. Full polling is the default; watcher is an accelerator. This does not qualify multi-endpoint identity/landing (OPS-11/12). [Daemon tests](../bootstrap/ioc-app/src/test/java/com/iocextractor/DaemonIngestE2EIT.java), [batch history](dev/native-capacity.md) |
| <a id="obs-d1"></a>OBS-D1 | `closed` | ADR-0017 diagnostics, bounded outcomes, exactly-once runner delivery and prepare→checkpoint→commit; empty producer ratchet. [Observability](dev/observability.md) |
| <a id="obs-d3"></a>OBS-D3 | `closed` | Event-local scalar fields use typed SLF4J key/value; MDC owns ambient correlation. Current Boot 4 encoder preserves the contract. [Observability](dev/observability.md) |
| <a id="obs-1"></a>OBS-1 | `closed` | Six-level severity→log-level mapping documented/tested, including FATAL/ERROR→error. [Observability](dev/observability.md) |
| <a id="obs-2"></a>OBS-2 | `closed` | Unmappable mutable projection values yield counted, value-free OPERATION/WARN diagnostics in completion; immutable exports retain REPORT. [CSV contract](dev/csv-output-format.md) |
| <a id="obs-3"></a>OBS-3 | `closed` | Generated log catalog, live action/reference ratchet and doc-sync. [Catalog](LOGGING-CATALOG.md) |
| <a id="obs-7"></a>OBS-7 | `closed` | E2 keeps publication failure primary and distinct observer failure suppressed, including self/suppression-disabled cases. [Spring publisher](../adapters/adapter-events-spring/src/main/java/com/iocextractor/adapter/out/events/spring/SpringControlEventPublisher.java) |
| <a id="obs-8"></a>OBS-8 | `closed` | E2 separates delivery/success observation and bounds fallback WARN RuntimeException; Error propagates. Synthetic provider failure was not a production Logback incident. [Spring publisher](../adapters/adapter-events-spring/src/main/java/com/iocextractor/adapter/out/events/spring/SpringControlEventPublisher.java) |
| <a id="obs-9"></a>OBS-9 | `closed` | L3-2 moved internal names to ioc.event.type and skipped disposition to ioc.operation.disposition; scalar profile excludes ECS event.type/category. Real encoder branches are tested. [Migration IT](../bootstrap/ioc-app/src/test/java/com/iocextractor/observability/LoggingMigrationIT.java) |
| <a id="obs-10"></a>OBS-10 | `closed` | L3-2 removed explicit error.type from the Throwable branch; real encoder tests assert bytes/JSON/no internal errors and retain type-only logging separately. [Migration IT](../bootstrap/ioc-app/src/test/java/com/iocextractor/observability/LoggingMigrationIT.java) |
| <a id="cfg-1"></a>CFG-1 | `closed` | Typed auto/explicit-long ID-start parsing replaced silent numeric fallback. [Configuration](dev/configuration.md) |
| <a id="cfg-2"></a>CFG-2 | `closed` | Startup identity↔sink/schema/registry/id-policy cross-validation. [Configuration](dev/configuration.md) |
| <a id="cfg-3"></a>CFG-3 | `closed` | Legacy stage-number runtime message removed with old aggregation/storage; operator diagnostics use configuration vocabulary. [Failure reports](dev/configuration-failure-reports.md) |
| <a id="cfg-4"></a>CFG-4 | `closed` | Strict unknown-key checks across YAML/CLI/system/env, schema-aware IOC_* matcher and value-free override reporting; removed tombstones retain migration hints. [Configuration](dev/configuration.md) |
| <a id="cfg-5"></a>CFG-5 | `closed` | Controlled YAML/semantic/Boot reports exclude rejected values and raw causes; arbitrary framework DEBUG logs remain outside that guarantee. [Failure-report contract](dev/configuration-failure-reports.md) |
| <a id="code-2"></a>CODE-2 | `closed` | Legacy CSV lookup duplication removed; domain classification and canonical row keys own the behavior. [Processing](dev/processing.md) |
| <a id="code-3"></a>CODE-3 | `closed` | One materialized ClassificationDecision per NETWORK IOC reused by providers/filter/TRACE. [Processing](dev/processing.md) |
| <a id="code-4"></a>CODE-4 | `closed` | Catalog discovery/doc-sync replaced a brittle fixed diagnostic-count assertion. [Diagnostics catalog](DIAGNOSTICS-CATALOG.md) |
| <a id="arch-4"></a>ARCH-4 | `closed` | E2 unwraps Spring's Error wrapper and propagates the original Error; real annotated listener and direct Error are tested. Service owns listeners/executors. [Events Spring](guides/libraries/events-spring.md) |
| <a id="exp-6"></a>EXP-6 | `closed` | Publish ledger pins unfinished/missing deliveries; only SUCCEEDED/ABANDONED permit slice deletion. [Export](dev/artifact-export.md) |
| <a id="ops-7"></a>OPS-7 | `closed` | Post-completion CanonicalArtifactsChanged nudges coalesced quiet-period export; interval/reconcile remains authority. ADR-0014 R2; [coordination](dev/event-coordination.md) |
| <a id="ops-9"></a>OPS-9 | `closed` | Exact remote terminal/quarantine purge precedes CAS local/receipt/ledger cleanup; replay is detached, recursive deletion forbidden. Live family breadth remains OPS-8. [Import](dev/dataframe-import.md) |

Completed background changes also include framework-free pipeline contracts in
platform-etl, configured source/sink charset enforcement, and empty source labels
with SOURCE.MARKERS_UNMATCHED instead of the old UNKNOWN sentinel.
