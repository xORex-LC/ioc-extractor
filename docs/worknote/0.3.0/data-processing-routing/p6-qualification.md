# P6 customer qualification and retirement review

Status: local correctness qualification in progress, 2026-09-30. Published operator
instructions are in [IOC processing routes](../../../guides/ioc-processing-routes.md);
this release worknote records the evidence and its limits.

## Behavior evidence

| Boundary | Executable evidence | Result |
|---|---|---|
| Selected document through Router, canonical SQLite and projection | `CustomerRoutingPipelineIT`, using `application-customer-routes.yml` layered on the golden artifact catalog and exact `customer-routes/*.csv` fixtures | Two URL paths become one `masks.mask = best-malware.com`; an IP with port/path becomes `ip_list.ip = 10.93.12.187`; the original-view blacklist retains three complete network values; hash and aggregate branches still execute. All five public CSV byte streams are compared in an isolated output directory. |
| Unselected document compatibility | `GoldenPipelineIT` and committed exact public CSV fixtures | Complete artifact bytes, row counts, revision and repeated-observation behavior are compared under unchanged configuration. |
| Selected processed import | `RouterProcessedImportRowPreparerTest`, `RouterSelectedImportDeliveryIT`, `ProcessingPlanCatalogTest` | Whole-cell admission, explicit source/output authority, missing/NULL/VALUE, compound conflict, recovered warning and strict failure. The integration test starts the production Spring context from `application-selected-import-production.yml` layered on the golden artifact catalog. Real binding/preflight, selected preparer, extractor, classifier, Camel runtime, local claim and strict CSV reader join the production processing service. It drives staging, COALESCE, canonical promotion and receipt-based finalization after stage removal, then checks the local terminal report. |
| Final identity and multiplicity | `PrepareRoutedArtifactsStageTest` | Host-key reduction retains one mask while original blacklist URLs remain distinct. A synthetic `(IP, country)` identity retains two rows; an IP-only identity selects one. There is no production country field or artifact. |
| Durable authority/recovery | P5 policy admission tests, `DataframeImportRecoveryServiceTest`, `JdbcCanonicalImportWriterContractIT`, `JdbcCanonicalLifecycleWriterIT` | Document policy changes require a drained intake; unsealed import pins are compared before restaging; committed imports finalize from receipts; document writes remain per artifact, import promotion cross-artifact. These existing receipt suites are shared with R5 rather than copied. |

The selected-route fixture is a synthetic document; it does not claim a live
customer feed or SMB evidence. The selected-import integration test uses a
temporary local source, service/dataframe SQLite databases and private workspace.
It exercises real local ownership and terminal archiving, but drives the
post-commit crash seam explicitly in the test; it is not a process-kill test.
Provisioned SMB evidence remains outside this local qualification. The paired
selected-route resource measurement below closes the local before/after gap.

## Resource observations

The reusable R5 synthetic Router profile was rerun with `make
router-qualification SIZE=1000` on Java 21, 12 reported processors and a
`-Xms128m -Xmx512m` JVM. Its 18 profiles covered 1/4/16 branches, 1/4 callers,
success, blocked failure and recovery. In the one-caller success profiles,
compile time was 99–117 ms, startup 379–419 ms, and allocated bytes per input
were 12,870 / 24,403 / 68,125 for 1/4/16 branches respectively. Across all
profiles retained post-GC heap growth was 136–247 KiB. The report is retained
locally at `.dev/router-qualification/router-1000.csv`; it measures neutral
Router execution, not IOC parsing, SQLite or a customer throughput SLO.

The existing managed-import reference load was rerun with
`tools/dev/dataframe-import-load.sh --profile insert --size 1000`. It accepted
1,000 rows, with 363 ms staging, 409 ms promotion, 86.2 MB observed peak Java
heap and a 991,232-byte sealed stage. The local report is under
`.dev/dataframe-import-load/insert-1000-20260929T141718Z/report.md`. This is
the shared disk-workspace/canonical writer profile, not a selected IOC route;
therefore its memory numbers are a downstream reference and cannot establish a
before/after route overhead. They are separate from deterministic offline
correctness tests and from provisioned external-transport evidence.

### Paired production-composition comparison

`tools/dev/processing-route-comparison.py --document-rows 8000 --import-rows
2000 --pairs 3` was repeated on 2026-10-01 against commit `7d0c9844` plus the
sampler fix. The [current raw report](qualification/p6/processing-route-comparison-sampler-fixed-20261001.json)
and [earlier report](qualification/p6/processing-route-comparison-20261001.json)
are versioned; current per-run logs are retained locally under
`.dev/processing-route-comparison-sampler-fixed-20261001/`. The probe starts a new
Spring context and isolated SQLite/workspace in each JVM, then measures only
the synchronous workload after startup with `-Xms128m -Xmx512m`. Pair order
alternates. Both configurations use the same real extractor, classifier, mapper
and persistence; the selected path uses the real Camel runtime. The import
configurations differ only by `processed-route`, while the document
configurations differ by activation of the selected plan and its catalog. The
physical document has 8,000 domain occurrences and 20 distinct domains
(99.75% repeats); the CSV has 2,000 rows and the same 20
distinct domains. Bare domains are deliberate: host-view selection must leave
the output equal to the compatible view while still exercising the selected
route for every occurrence/row. The script checks exact public CSV bytes and
canonical keys for the document, plus final import fields/keys, 20 accepted and
1,980 COALESCED stage rows, and the `(20 accepted, 0 rejected, 20 mutations)`
canonical receipt in every pair. All three pairs passed.
`ProcessingRouteComparisonTest` verifies that status-read failures and sampler
interruption abort the probe instead of publishing partial memory metrics.

| Workload, median of three fresh JVMs | Compatible | Selected | Selected / compatible |
|---|---:|---:|---:|
| Document wall time | 1,631 ms | 2,728 ms | 1.67× |
| Document input throughput | 4,904 occurrences/s | 2,933 occurrences/s | 0.60× |
| Document main-thread allocations | 218.3 MB | 531.1 MB | 2.43× |
| Document sampled peak Java heap | 112.6 MB | 123.0 MB | 1.09× |
| Document sampled process RSS high-water | 358,704 KiB | 372,852 KiB | 1.04× |
| Import wall time through terminalization | 870 ms | 1,615 ms | 1.86× |
| Import input throughput | 2,300 rows/s | 1,239 rows/s | 0.54× |
| Import main-thread allocations | 78.2 MB | 121.6 MB | 1.55× |
| Import sampled peak Java heap | 119.1 MB | 118.8 MB | 1.00× |
| Import sampled process RSS high-water | 406,332 KiB | 410,676 KiB | 1.01× |

The time includes document read/extraction/preparation/canonical write or
import admission/staging/promotion/terminalization, respectively; it excludes
Spring startup and fixture generation. The throughput denominator is all input
occurrences or CSV rows, not only the 20 retained identities. Allocated bytes
are the calling thread's `ThreadMXBean` delta, not process-wide allocations:
background and short-lived threads are outside that counter. A 10 ms sampler
records Java heap and Linux `VmHWM`; sampler failure or interruption aborts the
run before metric publication. Those memory peaks include previously
reached startup high-water and are not incremental route allocations. These
limits make the allocation comparison directional, while paired time and
equivalent-result checks establish the end-to-end overhead on this host.

The provisional regression envelope is median selected/compatible wall time
at most 2×, calling-thread allocations at most 3×, sampled heap and RSS each
at most 1.25×, and selected RSS below 1 GiB. The 2× wall bound matches the
existing duplicate-heavy aggregate load guard; 1 GiB is the service memory
envelope used there. The allocation bound admits the observed per-occurrence
selection and classification cost with some headroom, but the 2.43×
document churn is a concrete optimization target if larger duplicate-heavy
feeds or customer latency limits appear. All current medians pass, but import's
1.86× time ratio has little margin to the 2× guard. Its three paired ratios
range from 1.52× to 1.89×, so a customer SLO requires more runs and a
representative input mix. These are
local regression guards, not customer throughput SLOs; larger sizes, mixed
IOC types and provisioned SMB remain separate qualification dimensions.

## Retirement audit

`ConfigurableRowMapper` is the single field evaluator in `ioc-processing` for
both compatible and selected flows. `IocProcessingOperations` and
`IocProcessingRouteAdapter` are shared by selected document and import flows.
The old document stages and `CsvProcessedImportRowPreparer` remain reachable
only for configurations without their respective selections. Deleting them
would break the explicit legacy-configuration contract in the plan; there is no
dead second mapper or Router interpreter to remove. The selected path bypasses
the compatibility dispatch rather than running both.

## Activation boundaries and follow-up

- The operator guide describes complete artifact coverage, strict preflight,
  drain/pin behavior, side-by-side output inspection, no backfill and rollback
  with matching DB snapshots. A fixed TTL ages old rows; disabled lifecycle
  does not.
- The shipped original-view blacklist mapping puts an IP with port/path in
  `forbidden_url`, even when `ip_list` stores the cleaned IP. Operators wanting a
  bare blacklist IP must select the cleaned view for that artifact and review
  its provider and identity contract.
- A complete provisioned local/SMB delivery and larger mixed-type loads remain
  useful if a customer throughput or memory SLO is introduced. The paired
  local comparison above and the earlier reference load have distinct scopes.

The final worktree must pass `make docs`, `make verify` and the separate
`make pmd-analysis` gate. The exact-HEAD results are recorded by `make context`
and summarized in the implementation handoff; analyzer reports must be
reviewed, not just their exit status.
