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
