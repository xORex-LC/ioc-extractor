# CAP-7A — Bounded document source processing

Status: implementation in progress. Reference: `381c4ea0bf788d0e61276f7503dbc8ddcc274be5`.
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

Parser admission and compatibility evidence will be recorded before this stage
is declared complete. Existing CAP-6 failures remain failures, not replaced by
source-only measurements.

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

Implementation commits are being split by responsibility: `8bba0be2` introduces
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

Runtime implementation is committed at `f9c09a8f`. The unchanged PMD policy
passed before the final application cleanup correction. The preceding full
verification passed all functional suites and exact SpotBugs/CPD gates but
reported ingest missed branches 138 against 135. Retry/capacity boundary tests
were added; the full final gate and capacity measurements remain pending.
