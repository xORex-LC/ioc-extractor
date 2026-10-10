# CAP-7C C6: isolated operational candidate

Scope: implement and deterministically qualify the migration/ownership/readiness/
cleanup mechanisms that can proceed while C5 remains open. SQLite is the only
release backend. This does not accept ADR 0041, activate installed state, change the
default Spring composition, or turn G6 into an accepted capacity result.

## Implemented mechanisms

| Mechanism | Implementation and authority |
|---|---|
| Drained upgrade | Existing service-ledger checks; native format 12 and exact identity/public allocator catalog checks; atomic offline conversion to isolated format 13 |
| Native fact adoption | Existing NULL-aware typed chunk/address framing, at most 256 rows / 1 MiB retained per chunk; literal fields/times/deadlines; private/internal high-water initialization reuses the current allocator within the conversion transaction |
| Incompatible binary | Default format-12 migrator refuses converted format 13 before reconciliation; operational reopen also checks format and configuration fingerprint |
| Process ownership | Local adjacent file lease held for the complete operational scope; competing cooperating instances fail closed; shutdown refuses release under live borrowed work |
| Restart eligibility | Obtain exclusive process lease first; readiness remains closed; bounded pages release/audit an abandoned logical owner and cancel pending units; no age-based takeover |
| Receipt recovery | Existing canonical receipt and service-ledger authorities are unchanged; conversion and cleanup do not require CSV reprocessing |
| Readiness/health | Startup recovery state, SQLite settings/integrity, format/configuration pins, safe clock and maintenance outcome |
| Cleanup | Durable sorted-family rotation and existing per-family keyset progress; one bounded page per backstop call; unreferenced terminal control headers reclaimed after children/versions/anchors are gone |
| Rollback | Coordinated stopped backup of service/dataframe databases and owned files restored before running the native migrator again; no schema-number downgrade |

The owning API is adapter-private. It owns no second scheduler, retry queue,
database pool or mutation engine. Work must borrow ports inside its `use` lifetime.
Startup and maintenance page hooks are ready for the existing service lifecycle
and backstop composition; they are not registered in the default application.

The process lease coordinates cooperating candidate instances. It does not fence
an older native executable, protect unsupported filesystem locking, or identify
arbitrary external readers. Conversion requires all native processes stopped.
The stopped full recovery point remains necessary even when ledger drain passes.

## Deterministic qualification

`StagedPublicationOperationsIT` adds one Failsafe integration class, currently
30 invocations, with real file-backed SQLite and bounded worker coordination:

- Populated native masks/hashes import, literal all-family equality, IDs,
  deadlines, aliases, slots, receipt and provenance preserved across conversion.
- Committed import replay with its stage deleted; no second canonical effect.
  A new post-conversion delivery also restarts from PROMOTING and advances the
  real service ledger to CANONICAL_COMMITTED from receipt with the stage absent.
- More than one transport chunk (300 native rows) copied without dropped facts.
- Expired lifecycle remains expired after migration; its next observation creates
  new internal IDs and history rather than renewing it.
- An oversized native field or combined fields exceeding the row byte bound fail
  conversion; tables, format and copied structures roll back, followed by a
  successful corrected retry.
- Nonterminal import, ingestion claim and export run block conversion before
  canonical changes. These are real existing ledger states, not an inbox test.
- Native identity/catalog/allocator drift, inactive lifecycle, untrusted catalog
  identifiers, service-schema drift, changed configuration pins and incompatible format fail
  closed; the default migrator rejects converted state.
- Exclusive lease prevents a second owner in this JVM and in an independent
  child JVM until its bounded termination; live worker prevents lease release;
  workers have timeout/termination assertions.
- 257 abandoned units drain in bounded pages across reopen; no committed fact
  changes and the abandoned owner is audited once.
- Maintenance rotation/keyset progress survives reopen; obsolete controls are
  reclaimed while latest facts and the migration anchor remain.
- Injected cleanup failure and corrupt maintenance cursor close health readiness
  until a successful page; unsafe lifecycle UTC independently closes health.
- Health succeeds with a one-connection pool: clock inspection finishes before
  borrowing the status connection, without nested connection acquisition.
- An artifact without public ID preserves its burned internal allocation range.
- Coordinated stopped restore recovers native canonical facts, terminal service
  delivery and owned-file contents before native migration is allowed again.

The suite inventory changes from 79 to 80 integration classes and 296 to 297
deterministic classes, with 222 fast classes and five external shells unchanged.
The same-change test lifecycle/documentation review records this legitimate suite
expansion. Coverage floors, analyzer scopes and existing count ratchets are not
relaxed.

## Retained development evidence

- `.dev/c6-focused-1.log`: expected inventory guard failure while the newly added
  integration class had not yet been reflected in the reviewed inventory.
- `.dev/c6-focused-2.log`: one diagnostic-order assertion and one oversized SQL
  fixture construction failed. Stable ledger-check ordering and bound parameter
  construction corrected the tests; no retry hides the failure.
- `.dev/c6-focused-3.log`: initial nine operational invocations passed.
- `.dev/c6-focused-4.log`: 12 invocations passed after catalog/expiry checks.
- `.dev/c6-focused-5.log`: 14 invocations passed, including bulk adoption and
  observable maintenance failure.

- `.dev/c6-focused-6.log`: two negative fixtures used the wrong control-table name;
  corrected to the actual canonical control schema.
- `.dev/c6-focused-7.log`: 26 operational invocations passed.
- `.dev/c6-focused-8.log`: 28 invocations passed, including cross-process ownership,
  id-less allocation and real service-ledger receipt-only recovery.
- `.dev/c6-focused-9.log`: 30 invocations passed, including service-schema refusal
  and single-connection health; 5.935s for the operational suite.
- `.dev/c6-verify-1.log`: functional suites passed, but the branch missed-count
  ratchet and exact SQL-finding identities rejected the first full gate. More
  supported error/catalog/ownership cases were added; no coverage floor changed.
  [C6 SQL review](cap-7c-c6-sql-review.md) records three exact false positives and
  re-anchors two existing DDL findings after method/bytecode movement.
- `.dev/c6-verify-2.log`: the exact-finding registry rejected an unregistered
  review-trigger name. The entries now use the existing query-boundary trigger;
  verifier policy was not changed.
- `.dev/c6-verify-3.log`: the expanded operational suite and full deterministic
  gate passed before the final service-schema/health additions. Aggregate coverage
  was 92.30% lines / 85.75% branches; raw SpotBugs contained 165 accepted findings
  and zero visible findings; CPD retained 24 groups.
- `.dev/c6-pmd-1.log`: the adopted policy passed with 20 findings, unchanged.
- `.dev/c6-watchlist-1.log`: 80 advisory findings, versus 79 previously. The one
  new `PreserveStackTrace` warning points to `openOwned` rethrowing the original
  fatal `Error` after releasing the lease. The original throwable and suppressed
  cleanup failure are retained; no rule exclusion or watchlist acceptance was added.

Final worktree/HEAD verification, PMD, watchlist, documentation and exact raw
analyzer review are required after the implementation/documentation commits.
Their logs are retained separately as `.dev/c6-final-verify.log`,
`.dev/c6-final-pmd.log`, `.dev/c6-final-watchlist.log` and `.dev/c6-final-docs.log`.
The earlier development passes above do not establish freshness for a later HEAD;
`make context` is the freshness authority.

## Remaining promotion boundaries

1. **C5 remains open.** No whole-service migration/publication occupancy, WAL,
   memory, disk or eligible-control latency acceptance is inferred from this suite.
   G6 remains **NOT_ACCEPTED**.
2. **C6 activation remains open.** Resource qualification and ADR acceptance must
   precede default Spring composition/production migration registration and live
   deployment. No installed service is changed by this work.
3. **Coordinated deployment backup is mandatory.** The existing automatic deployment
   transaction backs up `var/db` only. It must cover owned files/configuration and
   qualify the complete stopped recovery point before staged activation. The new
   published rehearsal procedure explicitly rejects a database-only restore here.
4. **Promotion pin compaction remains conservative.** Control headers may be removed
   only when physically unreferenced. Document/import identities/pins remain because
   receipt retention alone does not establish that service coordination can never
   retry them. A durable terminal acknowledgement/retention transition must be
   designed before deleting these identities. C5 must include this retained growth.
5. **Backstop wiring is gated.** Page hooks persist progress and failures, but automatic
   startup/periodic scheduling and the selected port bundle are not enabled in the
   default application. They must switch together with all committed readers and
   controls, not through individual native-writer replacements.

See the published [capability](../../../dev/staged-canonical-publication.md) and
[rehearsal procedure](../../../guides/staged-publication-rehearsal.md). Full C6
promotion is not declared complete from this isolated operational implementation.
