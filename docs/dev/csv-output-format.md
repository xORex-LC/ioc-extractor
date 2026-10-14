# CSV output format

The CSV adapter writes mutable canonical projections and immutable export slices
using the same byte policy. The policy controls record endings and quoting,
independently for headers and data. Import parsing has its own contract dialect;
changing output settings does not change an import contract.

## Runtime flow

```text
typed configuration -> semantic preflight -> ExportFormat
    -> canonical snapshot -> shared CSV formatter -> temporary output
    -> atomic projection replacement / verified immutable slice publication
```

Bootstrap resolves the policy once for both output paths. The application model
contains framework-free enums. Commons CSV handles quoted fields in the adapter;
strict unquoted output validates each complete record before appending it.
Neither formatter changes canonical values, identities, TTL or provenance.

## Invariants

1. Record endings are explicitly LF or CRLF, never the host platform default.
   The setting separates records; it does not rewrite embedded line breaks in
   quoted cell contents.
2. Headers and values independently select `all`, `minimal` or `none`. `all`
   quotes every non-null cell. Missing values use the unquoted null marker in
   every mode. `minimal` delegates ordinary quoting and escaping to Commons CSV,
   and also quotes literal text equal to the null marker.
3. `none` never inserts quotes or escape characters. Delimiter, quote, CR and LF
   in a cell are errors. Data equal to the null marker and a single-column empty
   text record are also rejected because their meaning would be ambiguous.
   Empty cells in a multi-column record remain representable.
4. A null marker cannot itself contain CSV syntax. A consumer must distinguish
   quoted text from an unquoted null marker to preserve literal `NULL`; managed
   import's separately configured null-literal rules remain unchanged.
5. Legacy settings retain the exact v1 CSV bytes, manifest bytes and plan hash.
   A changed layout contributes every policy to the plan hash and emits manifest
   v2 with `record_separator`, `header_quoting` and `value_quoting`. The codec
   reads both versions; v2 requires all three fields. Unknown versions, policies
   and fields fail closed. A v1-only consumer must be upgraded before enabling
   the new layout.
6. Recovery reads the saved manifest and files. It never rewrites an old slice
   using the current configuration. CSV layout changes require no database
   schema migration and do not change artifact schema or mapping hashes.

## Failures and recovery

| Failure | Outcome |
|---|---|
| Invalid selector or CSV grammar | Configuration fails before runtime infrastructure starts. |
| Unrepresentable cell in `none` | The output attempt fails with a column position, without the raw cell value. |
| Mutable projection failure | Temporary output is removed; the previously installed file remains. Canonical data may already be committed and is not rolled back. |
| Immutable materialization failure | No final slice or `_SUCCESS` is published; partial staging follows normal export failure/recovery handling. |
| Restart after materialization | Existing checksum, manifest and marker evidence controls recovery, independently of the current format. |

Mutable projections retain diagnosed charset replacement only with `all` for
both headers and values. Selecting `minimal` or `none` for either uses strict
encoding: replacement could otherwise inject an unquoted delimiter or quote.
Immutable slices always use strict encoding because lossy bytes would violate
the export contract. UTF-8 is the default and supports all valid Unicode text.

## Configuration

For LF, quoted column names and strictly unquoted data:

```yaml
ioc:
  sink:
    csv:
      record-separator: lf
      header-quoting: all
      value-quoting: none
```

For unquoted ordinary headers and quoted data, set `header-quoting: none` and
`value-quoting: all`. Use `minimal` when values containing CSV syntax should be
quoted automatically. Omitted options preserve `crlf`, `all`, `all`.

These settings apply to all artifacts and export profiles sharing `ioc.sink.csv`.
They do not modify historical files or deploy configuration to an installed
service automatically. A mutable file receives the new layout on its next
projection; changing settings alone does not mark canonical generations dirty.
The immutable export pre-gate includes the new plan hash and therefore forms a
new slice even when canonical rows did not change.

## Sources of truth

- `IocProperties.Sink.Csv`, `CsvFormatConfiguration` and `IocConfigPreflight`:
  binding, defaults and admission.
- `ExportFormat` and `ExportPlan`: policy, wire version and byte fingerprint.
- `CsvRecordFormat`, `CsvArtifactProjection` and `CsvSliceMaterialization`:
  shared formatting and filesystem boundaries.
- `JacksonSliceManifestCodec`: exact v1 compatibility and explicit v2 fields.
- `CsvRecordFormatTest`, `CsvArtifactProjectionIT`, `CsvArtifactSliceWriterIT`
  and `JacksonSliceManifestCodecIT`: exact bytes, ambiguity and recovery.

## Update triggers

Update this document when changing defaults, null interpretation, accepted
unquoted values, wire versions, fingerprint inputs or atomic output boundaries.

## Related documents

- [Artifact export](artifact-export.md): immutable publication and recovery.
- [Storage](storage.md): canonical truth and projection ownership.
- [Configuration guide](../guides/configuration.md): complete operator surface.
- [ADR 0043](../ADR/0043-configurable-csv-output-format.md): compatibility decision.
