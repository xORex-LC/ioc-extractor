> English translation of the historical Russian record. Decision/status/dates are preserved. [Russian original](ru/0015-retire-legacy-csv-lookup-storage.md) (relative link addresses rebased on relocation).

<a id="0015--вывод-legacy-csv-lookupstorage-режима"></a>
# 0015 — Retire legacy CSV lookup/storage mode

<a id="статус"></a>
## Status

**Accepted, implemented in phases.** This decision records retirement of runtime
CSV lookup/storage after moving dataframe truth to SQLite/JDBC. The work plan
and identified risks were initially tracked in a local scratch note deliberately
outside published documentation. Durable implementation outcomes are recorded
below and in [KNOWN-ISSUES.md](../KNOWN-ISSUES.md).

**Update 2026-07-08. Fully implemented (phases 0–6), `./mvnw verify` green.**
Removed `adapter-lookup-csv`, `CsvIocSink`, `JdbcLookupRepository`, the
`LookupRepository` port and `EventAction.LOOKUP_LOAD`; stage deduplication is
batch-local; the ID baseline uses schema-aware `ArtifactIdBaseline`; configuration
uses `ioc.pipeline.
deduplicate` + tombstone validation for `ioc.lookup.*` and `storage.dataframe.type`.
The contract “a duplicate reaches the canonical repository and updates provenance”
is protected by regressions (adapter tests + an end-to-end double extract over
golden). **Open question 1 resolved:** operator seed import is unnecessary;
hand-filled CSVs are permanently out of the runtime story. Removed
`JdbcLegacyArtifactImporter` and `install.sh` seed hooks (`--lookup-seed`,
`seed_lookup`). Schema header references in `dataframe/` remain format references
only. Questions 2 (gapless IDs) and 3 (CFG-4 strict binding → tombstone removal)
remain open.

<a id="контекст"></a>
## Context

Early decisions used CSV files as lookup/storage references as well as output
artifacts: the pipeline could filter indicators through
`LookupRepository.contains(...)`, while `maxId(...)` continued the ID sequence
from existing CSVs. After ING-4 and ADR 0012, the target flow changed: canonical
business data lives in SQLite/JDBC, and `dataframe/*_generated.csv` is generated
projection/export.

A legacy fallback nevertheless remained in code:

- `adapter-lookup-csv` implements `LookupRepository` over CSV;
- `AppConfig` selects a CSV lookup/write branch for non-JDBC dataframe storage;
- `ioc.lookup.type/path/artifacts` look like a supported runtime contract;
- docs/packaging still sometimes describe hand-filled CSV as a lookup reference.

The main risk is `LookupRepository.contains` semantics, not the extra adapter
itself. On the JDBC path, the pipeline drops an already-known IOC before the
canonical repository. Thus `JdbcCanonicalArtifactRepository` never receives the
duplicate and cannot update `<artifact>_sources`, even though that repository
owns keep-first through `row_key`/`ON CONFLICT(row_key) DO NOTHING` and provenance update.

<a id="решения"></a>
## Decisions

<a id="1-sqlitejdbc-dataframe-storage--единственный-production-truth"></a>
### 1. SQLite/JDBC dataframe storage is the only production truth

Retire runtime CSV lookup/storage mode. CSV files remain only projection/export
surfaces, neither a system of record nor a deduplication source.

Consequence: non-JDBC dataframe storage must fail fast before removing old
branches. This closes the window in which a CSV oneshot fallback remains
reachable but loses storage-level deduplication after pipeline `lookup.contains`
is removed.

<a id="2-storage-level-dedup-принадлежит-canonical-repository"></a>
### 2. Storage-level deduplication belongs to the canonical repository

`lookup.contains` is no longer application policy. The canonical repository
handles cross-run/storage deduplication through durable identity:

- `row_key`: business identity;
- `ON CONFLICT(row_key) DO NOTHING`: keep-first public row;
- `<artifact>_sources`: provenance/occurrence accounting.

Pipeline deduplication remains only a within-batch optimization through
`Indicator.dedupKey()`.

<a id="3-batch-dedup-остаётся-осознанным-контрактом"></a>
### 3. Batch deduplication remains a deliberate contract

Within-batch duplicates do not increment occurrences. This is an explicit
decision, not a cleanup side effect: batch deduplication continues protecting
the writer from duplicates within one extraction run. Counting every appearance
in one source, if needed later, requires a separate pipeline semantics change.

<a id="4-lookuprepository-разделяется-а-не-переименовывается"></a>
### 4. Split `LookupRepository` instead of renaming it

`LookupRepository` combined two responsibilities:

- existence check (`contains`) for storage deduplication;
- ID baseline (`maxId`) for `id.start: auto`.

Remove `contains` with storage-level lookup. Move `maxId` to a narrow port,
for example `ArtifactIdBaseline.maxId(String artifactName)`, with a JDBC adapter
without hardcoded artifact names. Artifacts without an ID column must not query
SQL for a baseline.

<a id="5-id-allocation-не-переносится-в-repository-в-этом-cleanup"></a>
### 5. Do not move ID allocation into the repository in this cleanup

Retain the current `IdGenerator` in the first cleanup pass. Possible gaps after
duplicate conflicts are acceptable for unique ascending IDs: the database does
not increase `MAX(id)` for the conflicting row, preserving the inter-run baseline.

Gapless IDs or database-owned allocation are a separate decision. They affect
the old `ascending|descending` contract and must not be mixed with CSV lookup retirement.

<a id="6-seed-csv--отдельный-decision-gate"></a>
### 6. Seed CSV is a separate decision gate

Hand-filled CSVs no longer act as runtime lookup in JDBC mode. Therefore:

- if the requirement is dead, clean docs/packaging and remove
  `JdbcLegacyArtifactImporter` as a dead migration helper;
- if it remains live, provide an explicit canonical-DB import path (`ioc import`
  or startup/migration task), not hidden runtime CSV lookup.

<a id="следствия"></a>
## Consequences

- Remove `adapter-lookup-csv` from the Maven reactor and dependency graph.
- Remove `CsvIocSink`, the direct writer for legacy non-JDBC mode; retain
  `adapter-sink-csv` because it owns CSV projection, export-slice writers,
  retention store and completed-slice catalog.
- Remove `ioc.lookup.type/path/artifacts` from live configuration. Old external
  YAML needs a temporary tombstone/fail-fast, particularly for
  `lookup.deduplicate`, which moves to the new batch-dedup configuration key.
- Docs and packaging stop calling hand-filled CSV a runtime lookup reference.
- ADR 0012 and ING-4 remain the sources of the “SQLite truth + generated CSV
  projection” model; this ADR only removes the remaining legacy mode.

<a id="отклонённые-варианты"></a>
## Rejected alternatives

<a id="оставить-csv-lookup-как-fallback"></a>
### Keep CSV lookup as a fallback

Rejected: it maintains a second storage model that is no longer production
truth and hides the JDBC provenance defect. Continued support expands the test
matrix without a target benefit.

<a id="сразу-перенести-id-allocation-внутрь-repository"></a>
### Move ID allocation into the repository immediately

Rejected for this cleanup: technically it may reduce ID gaps, but affects
`ascending|descending`, explicit IDs and legacy import semantics. This is a
separate design, not a prerequisite for removing CSV lookup.

<a id="вернуть-storage-dedup-под-новым-именем"></a>
### Restore storage deduplication under a new name

Rejected: preserves the central defect — duplicates would not reach the
canonical repository and provenance would not update. Storage-level deduplication
must be a durable-write property, not a pipeline prefilter.

<a id="открытые-вопросы"></a>
## Open questions

1. Is operator seed import of hand-filled lists into the canonical DB needed,
   or do hand-filled CSVs permanently leave the runtime story? **Resolved
   (2026-07-08):** permanently removed; see the “Update 2026-07-08” in Status.
2. Will gapless IDs / DB-owned allocation be needed later, or are unique
   ascending IDs with gaps sufficient?
3. When will CFG-4 strict binding remove temporary tombstone fields for old
   `ioc.lookup.*` keys?
