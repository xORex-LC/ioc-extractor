# P4 processed-import execution evidence

Status: implementation seam complete; operator activation remains blocked by P5.

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

Accepted warnings are a separate `ImportRowWarning` channel. The private sealed
workspace stores them independently of rejected-row errors and caps retained
warnings by the existing maximum-row-errors resource limit. Dataframe migration
v12 adds `import_row_warning`; the canonical transaction copies warnings on final
accepted rows with `import_commit`, and receipt-based post-commit finalization
reads them into the safe terminal report without replaying input. A pinned v2
sealed stage remains readable with its original stage plan hash and has no warning
table; v3 is the new writer format. Unknown stage versions still fail closed.

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
artifact, requested slot, admitted cells and policies, source label, derived
formula/validator rejection and missing final key. The two pre-existing
`JdbcImportWorkspaceWriter` SpotBugs `THROWS_METHOD_THROWS_RUNTIMEEXCEPTION`
findings remain the same abort-on-failure behavior; their exact accepted
identities were reviewed and updated after adding the warning write.

P5 must bind an operator-selected plan to each versioned import contract, include
these bindings and semantic versions in policy identity, and define pinned
in-flight recovery before selecting the new preparer in production. The current
`CsvProcessedImportRowPreparer` remains the compatible production path until that
gate; its inference is not reused by the new route and can be retired in P6.
