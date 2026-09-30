# P4 processed-import execution evidence

Status: implementation seam complete. At this slice's completion, activation
was blocked by P5; the later [P5 activation](p5-policy-activation.md) attaches
explicitly selected processed contracts.

The import mapper now admits declared CSV cells as ABSENT, NULL or VALUE before
any processed output is interpreted. `AS_IS` retains its previous checks and
key timing. For `PROCESSED`, a driven preparer receives the admitted logical row;
the application then checks final row shape and values, preserves admitted
columns, branch roles, requested slots, source-label authority and merge
policies, and resolves
record/match keys from final fields. An expected input or mapping failure rejects
the entire logical row. Unexpected exceptions still leave the established retry
and failure path; no fallback is invented at this boundary.

The new `RouterProcessedImportRowPreparer` is an internal, explicit binding seam.
A binding names each `(artifact, target)` IOC input and each allowed output field;
it never infers a semantic carrier from the CSV mapper's provider name. Each VALUE
cell is refanged and passed through the shared whole-cell parser. ABSENT and NULL
cells are not parsed or flattened. Every input invokes the same admitted IOC
Router operations and view-specific mapper used for document occurrences. Branch
outputs assemble into one logical row, with a conflict rejecting different values
for the same target. A missing prepared primary branch rejects the row. Outputs
outside the contract artifact/field set or overwriting the source-label authority
are refused by the new Router binding. The compatible CSV preparer can still add
artifact-schema fields absent from the import contract's input columns. The
configured canonical key, including any future composite fields,
remains the final identity owner; no `(IP, country)` artifact is introduced.
After collecting every input contribution, a routed artifact with a non-null
output clears prior VALUE cells in route-owned targets that received no final
contribution. This permits URL-to-IP carrier moves without retaining a stale
URL in the composite key, while a null provider result alone does not command
a clear.

Accepted warnings are a separate `ImportRowWarning` channel. The private sealed
workspace stores them independently of rejected-row errors and caps retained
warnings by the existing maximum-row-errors resource limit. Dataframe migration
v12 adds `import_row_warning`; the canonical transaction copies warnings on final
accepted rows with `import_commit`, and receipt-based post-commit finalization
reads them into the safe terminal report without replaying input. A pinned v2
sealed stage remains readable with its original stage plan hash and has no warning
table; v3 is the new writer format. Promotion reuses the already verified stage
schema version rather than querying metadata again. Unknown stage versions still
fail closed. A warning for a different source row aborts the workspace; retained
warnings are capped independently of rejected-row errors.

Focused evidence covers final host identity after URL cleanup, preserved optional
ABSENT and explicit NULL cells, two-cell compound conflict, invalid whole-cell
input, recovered fallback as an accepted warning, accepted warning persistence
through stage and receipt, report serialization, and v2 stage adoption/promotion.
The new `RouterProcessedImportRowPreparerTest` adds one fast suite: the exact
source-universe ratchet moves from 215 to 216 fast suites and from 278 to 279
deterministic-offline suites. No existing test was reclassified or removed.

The quality review found a repeated effective merge-policy resolver in the CSV
and Router preparers; both now call the application-owned resolver also used by
input admission. Boundary tests cover final source-row identity, branch count,
artifact and role, requested slot, admitted cells and policies, source label,
derived formula/validator rejection and missing final key. They also pin final
cardinality after a derived NULL, accepted-warning removal when final identity
fails, and the machine-only formula exception. Bootstrap fixtures pin exact
binding admission, source-label authority, same-value coalescing, related-branch
preservation and unrecovered route failure. The two pre-existing
`JdbcImportWorkspaceWriter` SpotBugs `THROWS_METHOD_THROWS_RUNTIMEEXCEPTION`
findings remain the same abort-on-failure behavior; their exact accepted
identities were reviewed and updated after adding the warning write.
The Router import preparer keeps row-local assembly state and separates input
parsing, candidate merging, primary-output validation and final row assembly;
the adopted PMD policy and resource/size watchlist report no finding in these
changed members.

P5 subsequently bound an operator-selected plan to each versioned import
contract, included these bindings and semantic versions in policy identity,
and defined pinned in-flight recovery before selecting the new preparer in
production. `CsvProcessedImportRowPreparer` remains the compatible path for
unselected processed contracts and can be retired in P6.
