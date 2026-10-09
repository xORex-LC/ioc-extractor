# CAP-7A — Bounded document source processing

Status: implementation and scoped source qualification complete (2026-10-09).
G6 remains NOT_ACCEPTED. Reference: `381c4ea0bf788d0e61276f7503dbc8ddcc274be5`.
The frozen G6 budgets remain unchanged. CAP-7C/D and stand deployment are outside
this change. This note records decisions, failures and evidence as work proceeds.

## Protocol

- The admitted document workspace owns decoded and refanged UTF-16 text files.
  A fixed page cache exposes one complete `CharSequence`; regex anchors and long
  matches never see artificial chunk boundaries. Absolute positions remain Java
  UTF-16 offsets in the fully refanged text, as before.
- Each literal refang rule makes one ordered disk pass. A token crossing an I/O
  boundary is carried into the next buffer. Replacements are non-overlapping
  and replacement text is not rematched within the same pass.
- Lazy regex cursors run types in configured global priority order. Accepted
  nonempty spans are recorded in an indexed private scratch store; dropped spans
  cannot claim text. Decisions retain priority/match order, while accepted
  occurrences are read in position/stable encounter order.
- Marker cursors merge original and NBSP-normalized views by position, longest
  match, configured pattern order and original-view precedence. A single ordered
  cursor attributes occurrences with the nearest preceding inclusive marker.
- Match values, source text, intermediate disk use, cursors and parser working
  state have explicit ownership and bounds. Exceeding a limit rejects preparation
  before canonical promotion; no truncation or whole-document fallback is allowed.
- Source scratch is disposable, including on replay of a sealed preparation. It
  is removed before sealing and on failure/close; canonical receipts remain the
  authority after commit. Fatal worker errors stop dispatch visibly and leave
  durable admissions recoverable rather than stranding a hidden head job.

## Parser investigation

Tika 3.3.2 `BodyContentHandler(-1)` retains all text. Its HTML parser also builds
a complete jsoup DOM before SAX output; changing only the content handler cannot
fix the demonstrated million-occurrence HTML OOM. RE2/J 1.8 accepts a
`CharSequence` directly, so its adapter must stop calling `toString()`.

Parser admission and compatibility evidence is retained below. Existing CAP-6
failures remain failures, not replaced by source-only measurements.

## Gate-driven corrections

The first full deterministic run rejected the new code on the unchanged JDBC
missed-branch ratchet, one CPD duplicate and unreviewed SpotBugs identities.
Resource-lifetime, interruption, current-occurrence attribution and failure
cleanup cases were added. Regex adapters now share one finite cursor collector.
SpotBugs null-dereference warnings in HTML traversal were fixed by reading each
child once; rewrite closes its pinned local text handle.

A second parser-oracle fixture exposed a trailing newline/offset difference for
XML-style self-closing HTML tags. The streaming parser now loads Tika's own
`self-closeable-tags.txt`; the physical fixture compares exact decoded text.
Original/NBSP marker views also retain the batch multiplicity of zero-length
markers: the normalized view exists only when the source contains NBSP.

Dispatch admission uses `min(window, maximumConcurrentWorkspaces)` to prevent
ready later preparations from exhausting all leases ahead of the oldest job.
The one-lease regression checks actual ordered progress without increasing
the memory budget. Fatal source, promotion and cleanup failures retain durable
admissions for restart and cannot be presented as successful extraction.

The final ownership review also found two failure-path defects: a source-spool
delete in `finally` could mask the writer's fatal error, and a fatal prepared-handle
close could skip the remaining jobs. Both paths now attempt every independent
release, prefer fatal failure and retain secondary errors. Timed restart fixtures
verify that durable jobs remain retryable. Capacity health reports DOWN when the
dispatcher has stopped even if the durable ledger has no blocked rows (ING-15).

CAP-7A-SQL-TRUST reviews two new exact SpotBugs identities: numeric validated
PRAGMA limits and one private choice among three constant SELECT statements.
CAP-7A-OWNERSHIP reviews the spool close exception-policy identity against the
physical IO/runtime/fatal cleanup fixtures. No rule, coverage floor or missed-
branch baseline was weakened. The integration suite inventory increases by two
physical contract suites; naming, tags and discovery remain unchanged.

Cleanup also preserves a reused fatal exception instance: the same failure from
two independent release attempts must not trigger Java self-suppression and
hide the original Error. Physical workspace and timed dispatcher restart cases
cover this condition alongside independent secondary failures.

Streamed stage contracts compare refanged text, attribution and operational
trace decisions with finite batch oracles. Unicode blank input, writer-finish
failure, cancellation, inclusive marker positions and trailing marker validation
are covered without introducing framework dependencies into core tests.

Implementation commits are split by responsibility: `7ac45e46` introduces
the domain cursor/rewrite/claim contracts and non-materializing regex adapters.
Qualification tools retain an explicitly FAILED compact report on probe error
or timeout after removing all owned databases, source files and runtime copies.

The physical parser cohort includes framesets, comments retained inside repair
regions, nested SVG script/style content, missing image alt text, charset
fallback and reader cancellation. These preserve the finite parser oracle or
the explicit rejection contract. The source missed-branch ratchet remains three.
The pinned jsoup iterator emits EOF-closed ancestors; a redundant manual EOF
close pass was removed after verifying that contract. Empty and unclosed physical
HTML fixtures compare the complete output with Tika's previous parser.

PMD exposed additional complexity and two new throws from cleanup `finally`
blocks. The source JDBC cursor now closes both handles with normal suppressed
exception ordering. Active promotion cleanup explicitly retains the original
fatal worker error. Timed fixtures cover runtime, independent fatal and reused
fatal cleanup failures, plus a successfully completed document followed by a
cleanup failure: restart processes only the remaining durable occurrence.
Parser validation, page loading and decision tracing were separated by their
existing responsibilities rather than weakening the PMD count ratchets.

The application source owner also preserves a reused fatal exception from both
discard and close. Its release path avoids the self-suppression edge of generated
try-with-resources cleanup. Ordinary promotion retries preserve the same durable
observation/rank, and a zero/negative workspace capacity cannot start workers.

## Query plans

The project SQLite JDBC 3.53.2.0 engine was checked against the source scratch
schema and 100,000 accepted spans in a disposable in-memory database. Overlap
predecessor lookup uses `SEARCH claim USING INTEGER PRIMARY KEY (rowid<?)`;
exact-span lookup uses `SEARCH claim USING INTEGER PRIMARY KEY (rowid=?)`.
Ordered accepted occurrence traversal uses `SCAN decision USING INDEX
accepted_position`. The latter intentionally reads the complete accepted stream;
it does not perform a repeated full scan for each new match. The plan probe
temporary source/runtime directory was removed.

## Implementation identity and verification

The independently reviewable commits are `7ac45e46` (domain cursors and regex
adapters), `e9a2b447` (workspace-owned source processing and dispatcher ownership)
and `e525965c` (physical contracts and qualification-tool cleanup). ADR 0039 owns
the accepted source protocol; capability docs and module maps were updated with
the implementation. Canonical atomicity, receipts, TTL, slots and Router policy
remain unchanged.

The first measurements and deterministic verification identify `b228651bb23d`.
Its Git tree and the implementation tree at `e525965c420c` are both
`e71a9bf4e6a2b0b1c21b8ca2182c30ee0b18c243`. Original report identities are
preserved. All four whole-service cells use one frozen boot JAR, SHA-256
`af94759c63889569278c18e61b5c60487af8256218f91c607267d5f026f3764d`;
the source-only classpath has its own digest and fixture. No timing comparison
against a separately rebuilt reference is claimed.

Historical implementation-tree verification passed `make verify`, the unchanged
PMD policy, its resource-ownership watchlist, shell/tool contracts and offline
documentation checks. The physical source suites cover parser text/offsets,
refang boundaries, overlap priority, marker/source ordering, trace decisions,
quota rejection, cancellation and resource release. Timed dispatcher tests cover
fatal preparation/promotion/cleanup and restart. Exact SpotBugs identities are
reviewed, rather than suppressed by a broader rule; coverage and analyzer count
ratchets were not relaxed. Final committed-HEAD freshness is owned by
`make context` and its verification/PMD manifests, not this historical paragraph.

## Source-only diagnostic

[Compact evidence](qualification/capacity/cap-7a-reference.json) retains the
commands, fixture generator and executable identities, all four raw samples and
ordered value/type/source/count oracles. Each cell is a fresh JVM with actual
Spring admission, source parsing, disk refang, extraction and attribution.
The fixture consists of distinct defanged domains and one marker; this is a
source mechanism diagnostic, not the mixed-IOC complete-service workload.

| Occurrences | Format | Timed four-stage source path | Peak RSS | Post-GC heap |
|---|---|---:|---:|---:|
| 100,000 | HTML | 3.677 s | 308.90 MiB | 40.11 MiB |
| 100,000 | DOCX | 3.768 s | 311.88 MiB | 41.18 MiB |
| 1,000,000 | HTML | 28.262 s | 310.00 MiB | 40.02 MiB |
| 1,000,000 | DOCX | 28.426 s | 315.50 MiB | 41.06 MiB |

All four complete ordered occurrence oracles pass. Timed stages contain no
explicit GC. The peak sampler starts after Spring initialization and includes
oracle consumption and the separately marked post-GC sample. The raw
`live_heap_bytes` label means sampled heap used outside that post-GC diagnostic;
it must not be treated as retained live heap. Four single forks do not establish
primary medians, variability or a throughput ratio.

## Complete-service first-failure screens

The unchanged resource envelope is two CPU equivalents, `-Xms128m -Xmx512m`,
768 MiB cgroup high and 1 GiB cgroup max. The mixed fixture is mostly unique,
with URL/IP/hash values, defanging, duplicate occurrences and source markers.
The existing policy uses a 64 MiB preparation admission budget, 4 MiB configured
native cache, 2 GiB per-workspace and 8 GiB total private disk caps, and batches
of 128. Neither JVM limits nor workspace quotas were increased for CAP-7A.

Each cell requested three primary samples. Its first failed resource/workload
screen stopped the series, so only one screening sample is retained per cell.
No successful primary median or completed million-row throughput is reported.
The 100k timing column is the conservative local handoff-to-all-slices upper
window minus the configured two-second stability interval; it still includes
local scheduling. Million-row windows end at failure. None includes SMB.

| Occurrences | Format | Local window / failure window | Peak RSS | Result |
|---|---|---:|---:|---|
| 100,000 | HTML | 69.568 s local upper | 367.53 MiB | All five output oracles pass; local-time and writer limits fail |
| 100,000 | DOCX | 81.431 s local upper | 373.76 MiB | All five output oracles pass; local-time and writer limits fail |
| 1,000,000 | HTML | 634.401 s to failure | 347.83 MiB | Four source stages complete; private reducer exhausts its SQLite quota |
| 1,000,000 | DOCX | 531.839 s to failure | 351.95 MiB | Four source stages complete; private reducer exhausts its SQLite quota |

The completed 100k cells verify every canonical row, public field, provenance
occurrence and export slot for all five artifacts, all mutable generations and
three complete immutable profiles. Each has 16,677 masks, 16,677 IP rows,
43,358 hash rows, 33,354 blacklist rows and 90,054 aggregate rows, including
the independent warmup. Admission is terminal/succeeded and the measured run
is completed. This is local semantic evidence, not stand publication evidence.

The million-row HTML and DOCX runs both complete READ_SOURCE, REFANG, EXTRACT
and ATTRIBUTE without the previous source OOM. They then fail in
PREPARE_ARTIFACTS before the measured document's WRITE_ARTIFACTS stage. The
reported SQLite capacity exhaustion belongs to the private reducer's configured
`max_page_count` budget, including its journal reservation; it is not host disk
exhaustion. Those failure windows cannot prove complete-cycle million-row memory,
canonical output semantics or delivery throughput. Warmup commits are excluded
from the document's phase anchors.

All four sampled windows record zero swap, OOM and OOM-kill events. Cgroup
memory still approaches the 768 MiB high threshold and records high events:
disk spooling also consumes file cache. Window full-PSI fractions range from
0.000535 to 0.000682, below the 0.01 limit. The compact evidence retains anon,
kernel, file-cache, heap-used, CPU, WAL and private-workspace peaks separately.
Full-service sampled heap used is not a post-GC live-heap qualification.

## Remaining cost and disposition

| 100k format | Preparation | Write stage | Longest promotion hold | Total promotion wait |
|---|---:|---:|---:|---:|
| HTML | 31.689 s | 30.504 s | 13.313 s | 0.094 ms |
| DOCX | 36.421 s | 37.726 s | 16.420 s | 0.136 ms |

HTML million-row private preparation fails after 577.205 s.
DOCX million-row private preparation fails after 484.516 s.

These observations locate the remaining cost in preparation and work performed
while holding the writer, rather than promotion admission wait. Stage timings
do not separate SQL, serialization, journaling and disk latency; no exact CPU/IO
attribution is inferred without a dedicated profile. Long promotion holds still
violate the five-second limit and can delay concurrent control/export work.

CAP-7A implementation and its source contract qualification are complete. G6
remains **NOT_ACCEPTED**: the 100k local-time/writer gates fail, both complete
million-row workloads fail in the reducer, and remaining repeated, mixed-load
and SMB cells have not run. CAP-7D owns reducer capacity/cost investigation;
CAP-7C owns the canonical occupancy and visibility/receipt decision. Their
implementations require their own approved protocols and are not part of CAP-7A.
Retest the complete million-row resource window after these prerequisites work;
source-only memory results cannot substitute for that retest.

Every qualification-owned database, source snapshot, oracle store and runtime
copy was removed on success or failure, with process termination and empty
cleanup-error lists retained in the compact evidence. Only configuration,
diagnostic journals and small reports remain locally; no runtime database or
executable is versioned. The installed stand was not changed.
