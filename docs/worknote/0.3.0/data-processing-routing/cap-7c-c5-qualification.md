# CAP-7C C5 comparative service qualification

Status: harness implementation; capacity qualification and activation are open.

The first default-policy smoke fork exposed a candidate admission blocker:
256 KiB maximum rows, 4 MiB caches and a 64 MiB aggregate preparation budget
cannot reserve two canonical workspaces plus a document lease. The staged
configuration rejects startup. This is a failed default-configuration result,
not a resource pass. Comparative covering cells may explicitly use
`--bounded-row-profile` (16 KiB rows, 4 KiB fields and 1 MiB caches in **both**
variants, with the same 64 MiB aggregate budget). Those results cannot qualify
the larger default row/field contract. Fixing the candidate's worst-case chunk
reservation without weakening accepted input limits remains separate work.

The comparison keeps the corrected native SQLite implementation as the baseline.
The candidate is the private staged SQLite backend from C3/C4/C6. Neither fork
changes the packaged application, deployed service or production selector.
ADR 0041 remains proposed and G6 remains `NOT_ACCEPTED`.

## Measurement contract

`make sqlite-publication-capacity SQLITE_CAPACITY_ARGS='--jar
bootstrap/ioc-app/target/ioc-app-0.3.0-SNAPSHOT.jar --config <complete-policy.yml>
--output .dev/<new-evidence-directory> --rows 100000 --samples 3'` freezes the
packaged application and three explicitly selected test-only helpers. Both
variants use the same physical corpus and application policy. They start the
normal Spring application, intake, canonical processing, mutable projection and
immutable export paths; only the complete candidate persistence-port bundle is
replaced in the staged fork. A fresh active database is converted before intake.
Optional accumulated state is built through public document ingestion after
conversion. This is not a populated migration or stopped restore measurement.

Each fork runs in its own user-systemd unit with 2 CPU, `-Xms128m -Xmx512m`,
768 MiB memory high and 1 GiB memory maximum. Actual applied limits, packaged
bytes, helpers, policy, source commit, fixture hash, mode and cleanup are retained.
The same bounded Java agent in both primary variants observes already computed
writer wait/hold values at admission exit, including unsuccessful transactions.
A regression compares these values with the real guard's native metrics. Unlike
cumulative health maxima, the resulting maxima belong to the measured window.
The agent also reads cumulative JVM allocation accounting across all threads;
this includes HTTP, cleanup, projection and scheduler work, rather than only the
canonical caller. Agent/control overhead is included in both variants.

A containing worker makes a real clock control request once per second after the
preceding request completes and invokes actual history/version cleanup every
fifth request. Reports separate scheduled lateness, eligible-to-complete control
latency and exact admission queue wait. This is a declared closed-loop background
load, not an unlimited arrival-rate or sustainable mixed-intake claim. A worker
failure or incomplete shutdown prevents publication of successful metrics.

The reused service sampler retains raw current/peak RSS, sampled heap, CPU/GC,
cgroup anonymous/kernel/file accounting, PSI, WAL and workspace/disk bytes.
Separate `--diagnostic` forks retain JFR/NMT and post-GC heap; their timing is not
mixed with primary runs. The local interval remains a conservative handoff-to-
completed-slices window after subtracting the declared intake quiet period.
It is not an exact export-ready-to-dispatch measurement.

The independent disk oracle checks complete final fields, canonical keys,
positive canonical/lifecycle IDs, source summaries, mutable IDs and immutable
slots. The comparison rejects different input hashes or final oracle results.
An additional committed-family census retains receipt, history, version and slot
growth and checks source-summary references. Pending visibility, crash recovery,
TTL boundary semantics and deterministic conflict exhaustion remain established
by the C4 contracts; a family census does not substitute for those oracles.

`--import-artifact` derives one AS_IS contract from the unchanged schema,
identity and export catalogs; imported public IDs request sparse external slots.
`--import-artifact masks --processed-import` instead uses the existing production
selected-import fixture's host view, classifiers and COALESCE contract. The
unique fixture has one host per URL; `--shape collapse` sends distinct paths,
ports and query values into 20 hosts with identical admitted source cells. Its
independent oracle checks clean masks and reclassified match codes, while the
real delivery ledger and canonical receipt must report the coalesced key count.
This specific import fixture does not claim arbitrary source-cell conflicts or
full related-artifact routing. DOCX ZIP metadata is fixed so paired forks have
identical bytes and document source identities.

## Failure and acceptance policy

Both members of a pair execute even if one fails a resource screen. Further
pairs stop after a failed pair; failed logs, samples and measurements remain.
Raw evidence stays in the private `.dev` output. Business databases, CSV copies
and frozen runtimes are removed only after bounded service termination.

The adopted time, RSS, PSI, five-second physical writer and ten-second control
queue limits remain unchanged. Passing a covering cell is a screen result, not
completion of the whole C5/G6 matrix. Exact profile dispatch eligibility,
sustainable invalidation/intake, the complete artifact/import matrix, populated
conversion, cleanup backlog and diagnostic live-state limits must retain their
own evidence or remain explicitly open. No native-transaction fallback, smaller
workload, hardware increase or deferred cleanup makes a failed cell acceptable.
