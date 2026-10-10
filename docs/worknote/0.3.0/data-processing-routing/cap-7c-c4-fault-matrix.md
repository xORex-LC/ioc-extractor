# CAP-7C C4 fault and concurrency qualification

C4 exercises the isolated native staged-publication candidate. It does not
activate it in the application, accept the proposed ADR, migrate populated
databases, or close the capacity/operational gates C5/C6. SQLite remains the
release backend; G6 remains `NOT_ACCEPTED`.

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
(`.dev/c4-focused-11.log`). Complete final-worktree repository gates are required.
The execution record will identify their results after review of the generated
coverage, raw SpotBugs, CPD, PMD and resource watchlist reports. Intermediate
failed fixture/gate logs remain under `.dev/`; they are not acceptance evidence.
