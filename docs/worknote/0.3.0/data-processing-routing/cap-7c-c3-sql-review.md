# CAP-7C C3 SQL analyzer review

Evidence ID: `CAP-7C-C3-SQL`. Engine: SpotBugs 4.10.3.

The failed C3 reactor gate produced 32 identity deltas: 25 new SQL warnings
and seven changed bytecode locations in existing slot-registry warnings.
All 32 refer to generated SQL, not unbound data values. The raw reports contain
valid hashes and report zero analyzer errors and zero missing classes. The
filtered workflow report omits hashes for visible findings; that explains the
additional report-integrity error while these warnings remained unreviewed.
The raw identities remain authoritative. Neither the verifier nor its required
metadata was relaxed.

Reviewed boundaries:

- Table/column identifiers use the strict `DataframeColumn` grammar through
  `JdbcSql.quote` / `joinedQuoted`. Native shapes and persisted DDL originate
  from the adapter's own schema migrations, not imported files or YAML SQL.
- Hydration and selection predicates are private adapter call-site templates.
  Inputs, identities, source values, key hashes, deadlines and pagination
  limits remain prepared parameters. A physical SQLite regression carries
  a quote/SQL-looking Unicode value and a binary payload through hydration.
- Slot overlays are immutable maps whose keys and table names are validated
  at `JdbcExportSlotRegistry` construction. Their values select native or
  private membership tables without changing the existing slot policy.
- Journal cleanup receives only fixed table and key expressions from its
  private catalog. All ownership and limit values are bound parameters.
- Initialization DDL is a closed adapter-owned literal list. PRAGMA names are
  fixed call-site literals or validated quoted schema names; numeric cache
  limits come from validated limits or numeric SQLite settings.

The accepted registry below preserves exact type/hash/occurrence, primary
method descriptor and bytecode anchor. Presentation selectors are method-local;
raw exact matching still rejects a new finding sharing a selector. Existing
slot entries retain their IDs; only their reviewed changed anchors and evidence
are updated. No package-wide filter, detector disabling or quality-floor change
was introduced.

| Entry | Class | Method | Review trigger |
|---|---|---|---|
| CAP-7C-C3-SB-001 | JdbcImportStageReadPolicy | configure | sqlite-pragma-policy-change |
| CAP-7C-C3-SB-002 | JdbcPrivateChangeCapture | install | staged-publication-query-boundary-change |
| CAP-7C-C3-SB-003 | JdbcPrivateChangeCapture | install | staged-publication-query-boundary-change |
| CAP-7C-C3-SB-004 | JdbcPublicationCatalog | requireEmpty | staged-publication-query-boundary-change |
| CAP-7C-C3-SB-005 | JdbcPublicationProtocol | initialize | sql-format-template-change |
| CAP-7C-C3-SB-006 | JdbcPublicationReadLimits | read | sqlite-pragma-policy-change |
| CAP-7C-C3-SB-007 | JdbcPublicationReadLimits | write | sqlite-pragma-policy-change |
| CAP-7C-C3-SB-008 | JdbcPublicationSlotBase | hydrate | staged-publication-query-boundary-change |
| CAP-7C-C3-SB-009 | JdbcPublicationTable | createEmptyCopy | staged-publication-query-boundary-change |
| CAP-7C-C3-SB-010 | JdbcTypedPublicationStore | initializeEmpty | staged-publication-query-boundary-change |
| CAP-7C-C3-SB-011 | JdbcTypedVersionTable | create | staged-publication-query-boundary-change |
| CAP-7C-C3-SB-012 | JdbcTypedVersionTable | createView | staged-publication-query-boundary-change |
| CAP-7C-C3-SB-013 | JdbcTypedVersionTable | index | staged-publication-query-boundary-change |
| DATA-IMPORT-P3-SB-003 | JdbcExportSlotRegistry | populatePendingLifecycles | staged-publication-query-boundary-change |
| DATA-TTL-P7-SB-003 | JdbcExportSlotRegistry | releaseVanishedAssignments | staged-publication-query-boundary-change |
| DATA-IMPORT-P3-SB-004 | JdbcExportSlotRegistry | requireActiveLifecycle | staged-publication-query-boundary-change |
| DATA-TTL-P7-SB-004 | JdbcExportSlotRegistry | requireCompleteAssignments | staged-publication-query-boundary-change |
| DATA-TTL-P7-SB-005 | JdbcExportSlotRegistry | requireSeedableRows | staged-publication-query-boundary-change |
| DATA-TTL-P7-SB-006 | JdbcExportSlotRegistry | seedActiveAssignments | staged-publication-query-boundary-change |
| DATA-TTL-P7-SB-007 | JdbcExportSlotRegistry | upperSlot | staged-publication-query-boundary-change |
| CAP-7C-C3-SB-014 | JdbcPrivateChanges | read | staged-publication-query-boundary-change |
| CAP-7C-C3-SB-015 | JdbcPublicationHydrator | copy | staged-publication-query-boundary-change |
| CAP-7C-C3-SB-016 | JdbcPublicationJournalCleanup | delete | staged-publication-query-boundary-change |
| CAP-7C-C3-SB-017 | JdbcPublicationJournalCleanup | hasRows | staged-publication-query-boundary-change |
| CAP-7C-C3-SB-018 | JdbcPublicationSelection | minimumDeadline | staged-publication-query-boundary-change |
| CAP-7C-C3-SB-019 | JdbcPublicationSelection | select | staged-publication-query-boundary-change |
| CAP-7C-C3-SB-020 | JdbcPublicationSlotBase | hydrate | staged-publication-query-boundary-change |
| CAP-7C-C3-SB-021 | JdbcPublicationSlotBase | stream | staged-publication-query-boundary-change |
| CAP-7C-C3-SB-022 | JdbcPublicationVersionCleanup | scan | staged-publication-query-boundary-change |
| CAP-7C-C3-SB-023 | JdbcStagedExpiryStore | lambda$expireDue$1 | staged-publication-query-boundary-change |
| CAP-7C-C3-SB-024 | JdbcStagedExpiryStore | nearestDeadline | staged-publication-query-boundary-change |
| CAP-7C-C3-SB-025 | JdbcTypedPublicationChunk | insert | staged-publication-query-boundary-change |
