# P6 customer qualification and retirement review

Status: local correctness qualification in progress, 2026-09-30. Published operator
instructions are in [IOC processing routes](../../../guides/ioc-processing-routes.md);
this release worknote records the evidence and its limits.

## Behavior evidence

| Boundary | Executable evidence | Result |
|---|---|---|
| Selected document through Router, canonical SQLite and projection | `CustomerRoutingPipelineIT`, using `application-customer-routes.yml` layered on the golden artifact catalog and exact `customer-routes/*.csv` fixtures | Two URL paths become one `masks.mask = best-malware.com`; an IP with port/path becomes `ip_list.ip = 10.93.12.187`; the original-view blacklist retains three complete network values; hash and aggregate branches still execute. All five public CSV byte streams are compared in an isolated output directory. |
| Unselected document compatibility | `GoldenPipelineIT` and committed exact public CSV fixtures | Complete artifact bytes, row counts, revision and repeated-observation behavior are compared under unchanged configuration. |
| Selected processed import | `RouterProcessedImportRowPreparerTest`, `RouterSelectedImportDeliveryIT`, `ProcessingPlanCatalogTest` | Whole-cell admission, explicit source/output authority, missing/NULL/VALUE, compound conflict, recovered warning and strict failure. The integration test starts the production Spring context from `application-selected-import-production.yml` layered on the golden artifact catalog. Real binding/preflight, selected preparer, extractor, classifier, Camel runtime, local claim, strict CSV reader, sealed SQLite stage and canonical writer coalesce two URL paths on one final host key. The durable receipt finalizes the delivery after stage removal through the production processing service and local terminal report. |
| Final identity and multiplicity | `PrepareRoutedArtifactsStageTest` | Host-key reduction retains one mask while original blacklist URLs remain distinct. A synthetic `(IP, country)` identity retains two rows; an IP-only identity selects one. There is no production country field or artifact. |
| Durable authority/recovery | P5 policy admission tests, `DataframeImportRecoveryServiceTest`, `JdbcCanonicalImportWriterContractIT`, `JdbcCanonicalLifecycleWriterIT` | Document policy changes require a drained intake; unsealed import pins are compared before restaging; committed imports finalize from receipts; document writes remain per artifact, import promotion cross-artifact. These existing receipt suites are shared with R5 rather than copied. |

The selected-route fixture is a synthetic document; it does not claim a live
customer feed or SMB evidence. The selected-import integration test uses a
temporary local source, service/dataframe SQLite databases and private workspace.
It exercises real local ownership and terminal archiving, but drives the
post-commit crash seam explicitly in the test; it is not a process-kill test.
Provisioned SMB evidence and a comparable selected-route before/after resource
measurement remain outside this local qualification.

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
before/after route overhead. No new SLO or hard resource limit is inferred from
these samples. They are separate from deterministic offline correctness tests
and from provisioned external-transport evidence.

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
- A selected-import workload measuring route preparation together with the
  disk workspace, or a complete provisioned local/SMB delivery, remains useful
  if a customer throughput or memory SLO is introduced. The present reference
  load is intentionally labeled separately.

The final worktree must pass `make docs`, `make verify` and the separate
`make pmd-analysis` gate. The exact-HEAD results are recorded by `make context`
and summarized in the implementation handoff; analyzer reports must be
reviewed, not just their exit status.
