# O3–O4 implementation evidence

## O3: incremental document winners

The application selector owns a thread-confined accumulator with one complete
candidate per final key and stable first-key order. The routed preparation stage
and CSV occurrence-aware preparation consume that same transition. Mapping and
diagnostics still run for every occurrence. Legacy KEEP_FIRST dispatch and row
multiplicity are unchanged; import COALESCE remains untouched.

Selection regressions cover blank groups, ties, nonblank followed by blank,
whole-candidate metadata, immutable snapshots and KEEP_FIRST without evaluating
a selection field. CSV regressions assert duplicate/collision row multiplicity
and deferred ID reservations. The production Spring golden suite adds a
legacy collision scenario with deduplication both enabled and disabled, lifecycle
both enabled and disabled, asserting canonical uniqueness and provenance occurrences when lifecycle is
disabled. Its ID sequence is in memory (tested separately at materialization);
the durable lifecycle allocator remains unchanged.

The existing lifecycle writer rejects duplicate final keys during command
validation, before reservation or commit. The same fixture with lifecycle
active asserts that rejection and unchanged storage/allocator state, rather
than silently changing legacy rows to make the writer accept them. This
pre-existing limitation also affects identical admitted IOC occurrences with
upstream deduplication disabled; resolving it requires a separate accounting
contract, not extending O3 reduction into prepareLegacy.

The test universe deliberately gains ArtifactOccurrenceSelectorTest:
fast 218 → 219, deterministic-offline 285 → 286; integration remains 72 and
external remains 5. This is a new behavior suite, not a reduced coverage floor.

Focused selector and CSV preparation suites pass. The production golden
accounting method passes all four lifecycle/deduplication combinations. Full
reactor and static-analysis evidence is recorded after the combined O3–O4 slice.

## O3 isolated comparison

Five alternating compatible/selected pairs per workload, one disjoint warm-up,
8,000/20-key document and 2,000/20-key processed import. Both snapshots pin
`DEBUG=false`, `LOGGING_LEVEL_ROOT=WARN`, `-Xms128m/-Xmx512m`. Before is
ba46a10a; after is 3cfcad04. Fixture/configuration digests and every same-path
outcome and canonical/projection signature match across revisions. The harness
and resource settings are unchanged. Raw logs remain in ignored isolated
worktrees; reviewed summaries are [before](qualification/optimization/o3-before-warm.json)
and [after](qualification/optimization/o3-after-warm.json).

| Selected workload | Median time before / after | Calling-thread allocation before / after | Sampled heap before / after | Current RSS before / after |
|---|---:|---:|---:|---:|
| Document | 492.0 / 437.6 ms | 290.3 / 290.4 MB | 126.4 / 120.0 MB | 384,800 / 370,188 KiB |
| Import | 568.5 / 577.6 ms | 84.6 / 84.4 MB | 117.2 / 118.4 MB | 384,916 / 383,428 KiB |

O3 removes retention, not mapping/allocation. Document median time improves
11%, sampled heap 5% and GC count 9 → 6; allocated bytes remain effectively
unchanged. Import production code is unchanged; its time variation is not an
O3 gain. Independent forks and sampled peaks cannot establish universal latency
or heap bounds. Historical guards pass but do not constitute customer acceptance.

## O4: explicit semantic scopes

Application owns a separate document session handle and closes it before the
checkpoint or on failure. A stateless compatibility adaptation lives outside
the port package, preserving its interface-only ArchUnit contract. Bootstrap
carries the pure session through IOC views; the neutral Camel adapter remains
unchanged. Import scopes reuse to one logical row. Delivery-wide reuse remains
deferred; staging, source authority, COALESCE and receipt replay are unchanged.

The complete classification key includes source label/section and type. Both
classifier wrappers must pin the same policy object; a different operation
policy bypasses the cache. Host reuse retains only value/type and reattaches the
current source. Successful immutable results alone are admitted; failures,
diagnostics, occurrence metadata and mapped candidates are not cached.

Initial joint bounds are 256 entries, 1 MiB charged retention and 64 KiB per
entry. Exhaustion/oversize computes normally. The charge is conservative UTF-16
text accounting plus object/list allowances, not an exact heap limit.
[Calibration](qualification/optimization/session-budget.txt) preceded production
wiring and used 8,000 observations per caller:

| Profile | Callers | Policy calls | Retained entries | Charged bytes | Live heap after diagnostic GC | Current RSS |
|---|---:|---:|---:|---:|---:|---:|
| 20 repeated keys, 2k suffix | 1 | 20 | 40 | 348,420 | 1,498,888 B | 78,400 KiB |
| Unique keys, 2k suffix | 1 | 8,000 | 120 | 1,045,460 | 1,886,744 B | 119,744 KiB |
| Unique keys, 40k suffix | 1 | 8,000 | 0 | 0 | 1,742,384 B | 141,248 KiB |
| Unique keys, 2k suffix | 4 | 32,000 | 480 | 4,181,840 | 2,319,320 B | 142,704 KiB |

This standalone fixture intentionally exercises worst-case duplicated text in
classification results. Its post-GC snapshots/charged sizes demonstrate bounded
admission, not full application throughput or concurrent document memory.
Reproduce the retained calibration fixture from the repository root:

```bash
mkdir -p .dev/session-budget-classes
"$JAVA_HOME/bin/javac" -cp core/ioc-domain/target/classes:core/ioc-processing/target/classes \
  -d .dev/session-budget-classes \
  bootstrap/ioc-app/src/test/java/com/iocextractor/bootstrap/IndicatorSessionBudgetProbe.java
"$JAVA_HOME/bin/java" -Xms128m -Xmx512m \
  -cp .dev/session-budget-classes:core/ioc-domain/target/classes:core/ioc-processing/target/classes \
  com.iocextractor.bootstrap.IndicatorSessionBudgetProbe
```

Unit regressions cover source/section/type keys, same-policy wrapper reuse,
different-policy bypass, host source reconstruction, entry/size/oversize limits,
uncached failures, close guards and independent concurrent scopes. Stage tests
assert one open/close on success and operation failure. Camel-backed preparation
tests prove every occurrence still maps with its current source/position while
classification computes less. The source inventory deliberately gains
IndicatorProcessingSessionTest: fast 219 → 220 and deterministic-offline
286 → 287; integration/external counts remain unchanged. No coverage floor or
analyzer baseline is weakened.

The diagnostic agent now tracks derived-operation requests separately from
actual classifier entry calls, including cache misses inside the session. Its
existing original/derived totals remain computation counts; winner retention is
observed at accumulator updates. Instrumented forks remain separate from primary
cost measurements.
