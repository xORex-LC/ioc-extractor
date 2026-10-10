# CAP-7C C4 fault and concurrency qualification

C4 exercises the isolated native staged-publication candidate. It does not
activate it in the application, accept the proposed ADR, migrate populated
databases, or close the capacity/operational gates C5/C6. SQLite remains the
release backend; G6 remains `NOT_ACCEPTED`.

Implementation references are `c4b93a4c` (cancellation and fault suite),
`83112b94` (pending replacement/TTL/metadata oracles), `c93490ee` (sidecar race),
`8b282266` (all-family crash oracle) and `e683f298` (root failure preservation).

## Executable boundaries

`StagedPublicationFaultIT` owns a child JVM, real service/dataframe SQLite files,
native private workspaces and a sealed import stage. One committed seed survives
an 80-row, two-artifact fan-out import. Primary rows request distinct slots in
one namespace. The parent forcibly terminates the child without running Java
cleanup and reopens all process-local components against the same files.

Every hidden chunk is tested immediately before and after its commit. Additional
kill boundaries are private preparation, sealing, before publication, inside
the uncommitted visibility flip, and after canonical commit but before the
service-ledger transition. Before commit, matching/public tables expose only the
seed; after commit, all 160 imported artifact rows, aliases, source summaries,
slots and the full native receipt appear together. The actual promotion service
recovers the durable `PROMOTING` head. In the post-commit case the input stage is
deleted; recovery must leave every transported fact and authoritative allocator
unchanged and advance the ledger to `CANONICAL_COMMITTED`.

## Matrix and evidence ownership

| Required boundary | Executable oracle |
|---|---|
| Before/after every hidden chunk and seal | Forced child termination/reopen; seed remains visible; retry retains delivery pins and persists abandoned-fence evidence |
| Fan-out/receipt staged before visibility flip | Forced termination inside the uncommitted publication transaction; neither complete receipt nor partial artifact membership survives |
| Flip failure/cancellation | SQLite trigger abort, runtime exception, fatal error, and thread interruption; publication sequence, public facts and ledger do not advance; burned reservations are not reused |
| Commit before service-ledger transition | Real `DataframeImportPromotionService` resumes `PROMOTING` from receipt without stage, with identical canonical tables/IDs/provenance |
| Old worker resumes after new fence | Timed two-worker test; replacement remains SEALED while the obsolete worker resumes; its fence, pending state and all native facts remain unchanged, then it commits |
| Same-key document/import, reverse completion | Real shared observation registrations, ordered field origin, KEEP_FIRST identity and both source summaries; different ready units cannot overtake logical ownership |
| TTL equality/preparation crossing deadline | Actual expiry and slot reconciliation at deadline equality while import is prepared force one rebuild; new canonical/lifecycle IDs, fresh-publication deadline, old history/alias closure and lowest-hole slot reuse; C3 also covers deadline crossing without a control |
| Slot collision/control during preparation | Real snapshot control acquires survivor slot while import is paused; dependency conflict forces rebuild; survivor remains at 1, fallbacks use lowest holes and receipt records collisions |
| Metadata-only updates and renewal-only observations | Later registered observation advances ordered origin with equal public bytes; safe UTC advances inside publication, then renewal-only observation advances deadline; IDs, revision and generation remain stable; inherited native TCK covers merge/tri-state outcomes |
| Reader across publication/cleanup | One real SQLite snapshot checks every transported family while publication and bounded version cleanup run; after rollback it sees new facts and WAL truncation is not busy |
| Quota/corruption/shutdown | C3 actual workspace/hydration quota and interruption guards; C4 forced process death and missing/truncated sealed stage fail closed; restoring exactly the pinned bytes permits retry |

The new Failsafe suite adds one deterministic integration class. The reviewed
inventory becomes 222 fast / 79 integration / 5 external / 296 deterministic
offline classes. Coverage floors, analyzer baselines and discovery rules are
unchanged.

## Defects found during qualification

An interrupted promotion correctly refused publication, but the same interrupt
flag caused admitted failure cleanup to refuse its own transaction. Hidden work
remained sealed and the logical owner stayed occupied. The regression was
retained in `.dev/c4-focused-3.log` before the fix. Cleanup now clears an existing
flag only for its bounded cancellation/release transaction and restores it
afterwards. Cancellation before transfer, after a hidden chunk, before flip and
inside flip is tested for promotions; controls test cancellation before/inside
flip. No clearing occurs on the commit authorization path.

The second full reactor run (`.dev/c4-verify-2.log`) failed during the healthy
retry after cancellation: SQLite unlinked `native.db-wal` while `Files.walk`
was reading entry attributes. The iterator raised `UncheckedIOException`
before the per-file size check. Workspace accounting now uses `walkFileTree`
and observed attributes, tolerating only a missing known SQLite sidecar below
the requested root. Present sidecars still count; missing roots/main files,
access failures and symlinks still fail closed. There is no automatic retry or
quota relaxation. Deterministic visitor-failure regressions exercise this race
without timing dependence; all 59 workspace-suite invocations passed in
`.dev/c4-disk-focused-1.log`.

The subsequent full run (`.dev/c4-verify-3.log`) passed functional suites,
coverage and CPD, but SpotBugs rejected a nullable `Path.getFileName()` dereference
in the new visitor. An explicit filename guard and root-callback regression
preserve the original I/O error instead of replacing it with a null-pointer
exception. All 60 workspace invocations then passed in
`.dev/c4-disk-focused-2.log`; the module raw report returned to its 94 reviewed
findings, with zero visible findings, analyzer errors or missing classes.
No acceptance, suppression, analyzer scope or coverage baseline was changed.

## Remaining limits

Forced termination is local process-loss evidence, not a power-loss or broken
filesystem certification. Reclamation bounds and WAL checkpoint availability
are deterministic assertions; sustained retention, maximum WAL/RSS and eligible
control latency belong to C5. Failed cleanup still requires recovery; cleanup
cannot guarantee progress against permanently unavailable SQLite.

The isolated caller explicitly authorizes same-unit retry/reclaim. A new fence
invalidates even a still-running old worker; it is not proof that the old worker
is dead. Default composition, durable retry scheduling, takeover eligibility,
drained migration and coordinated rollback remain C6 responsibilities. The
service ledger continues to own forward delivery coordination.

## Verification

`make test-one MODULE=adapters/adapter-store-jdbc TEST=StagedPublicationFaultIT`
passed all 29 invocations with zero failures, errors or skips
(`.dev/c4-focused-11.log`). The 60-invocation workspace suite also passes; its
regressions cover the disk accounting correction and unchanged fail-closed limits.
The full reactor additionally executed all 29 fault invocations successfully
before the static-analysis rejection described above.

The resource/exception watchlist was reviewed after the corrections: 79 advisory
findings (67 CloseResource, 10 PreserveStackTrace, two NcssCount), unchanged from
C3, with no processing errors. The new disk visitor and changed publication
cleanup have no watchlist findings. The factory's six existing findings concern
returned lease ownership and rethrowing the same typed fatal error; ownership
callbacks, failed-open cleanup and original causes remain intact and exercised
by the workspace suite. Watchlist results do not refresh regular PMD evidence.

Repository completion requires `make verify`, `make pmd-analysis`,
`make pmd-watchlist` and `make docs` after this documentation synchronization.
Inspect the generated coverage, raw SpotBugs, CPD, PMD and resource watchlist
reports. `make context` and the local verification records must identify the
final HEAD, passing results, fresh verify/PMD evidence and a clean tree.
Focused checks never substitute for those gates. Intermediate failed
fixture/gate logs remain under `.dev/`; they are not acceptance evidence.
