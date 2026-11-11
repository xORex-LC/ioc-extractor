# com.iocextractor.adapter.out.manifest.json

## Purpose and structure

Serialization boundary for immutable-slice manifests. Follow the
[module dependency rules](../../../../../../../../../README.md).

| File / nested type | Responsibility |
|---|---|
| [JacksonSliceManifestCodec.java](JacksonSliceManifestCodec.java) | Public `SliceManifestCodec` implementation; mapping and version/JSON configuration |
| `ManifestDocument` | Private root wire names and field order |
| `FormatDocument` | Private CSV metadata and optional v2 layout fields |
| `ArtifactDocument` | Private artifact identity, schema/content hashes and row count |
| `CoverageDocument` | Private revision/time/upper-ID mapping |
| [package-info.java](package-info.java) | Package boundary |

Jackson annotations live only on these private document records. The application
[SliceManifest](../../../../../../../../../../../core/ioc-application/src/main/java/com/iocextractor/application/export/SliceManifest.java)
and related value records own semantic validation. A configured reader/writer
pair is retained; document records, lists and result bytes are created per call.
The codec owns no file, caller stream, executor or close lifecycle, and maintains
no per-manifest cache.

## Byte representation

Root order is `manifest_version`, `slice_id`, `run_id`, `profile`, `created_at`,
`output_mode`, `plan_hash`, `format`, `artifacts`. Explicit nested property order
covers format, artifact and coverage records. Arrays preserve model order;
the codec does not sort artifact names or normalize input to a canonical JSON
standard. Map insertion order elsewhere cannot reorder these record properties.

Encoding returns fresh UTF-8 JSON bytes with two-space indentation, LF newlines,
the qualified pretty-printer spacing and no trailing newline. Non-ASCII text
remains UTF-8; quotes/control characters use JSON escaping. Timestamps use
`Instant.toString()`. `output_mode` and v2 layout enum values are written
lowercase using `Locale.ROOT`; decoding maps them back case-insensitively.

`coverage.changed_at` is explicitly JSON null for zero revision; positive
revision requires a timestamp through `ArtifactCoverage`. The format record
omits null properties so v1 has no v2 layout fields. CSV `null_literal`, quote,
charset and record separator describe CSV bytes; JSON uses its own UTF-8
encoding, escaping and LF layout.

The CSV writer hashes the exact returned bytes for `_SUCCESS`; verification
hashes the original stored bytes. Whitespace/order-equivalent JSON can have a
different digest. Decoding and re-encoding stored JSON is not a way to recover
its original bytes or verify its original digest. Changing Jackson configuration
or its dependency baseline requires exact-byte compatibility checks.

## Version mapping

| Version | `format` layout behavior |
|---|---|
| 1 | Omit layout policy fields when encoding; decode as CRLF with ALL header/value quoting |
| 2 | Encode and require non-null `record_separator`, `header_quoting` and `value_quoting` |

Version 1 rejects non-null declarations of the v2 policy fields. Version 2 can
also describe the legacy layout when all three policies are explicit. The codec
does not upgrade/downgrade the model's version or select a profile's layout;
[ExportFormat](../../../../../../../../../../../core/ioc-application/src/main/java/com/iocextractor/application/export/ExportFormat.java)
and export planning own that choice. `SUPPORTED_VERSION` is currently 2, while
version 1 remains readable/writable. Other versions fail the version gate after
document decoding and before application-model construction. Stored slices keep
their declared layout when current configuration changes.

## Validation and failures

The private mapper enables unknown-property rejection and duplicate detection.
Mapping parses timestamps/enums and invokes application constructors, which
validate text, hashes, counts, identity, coverage and layout consistency.
It does not independently validate every JSON field's presence or disable all
Jackson coercions. For example, an absent primitive `rows` value becomes zero,
which the model accepts. Do not advertise this decoder as a complete JSON-schema
validator or rely on it to reject every missing/null scalar.

Null arguments to either public operation fail with `NullPointerException`
before Jackson processing. Unsupported versions raise `IocExtractorException`.
Encode wraps Jackson processing failures; decode wraps parsing, mapping and
model-validation exceptions as `IocExtractorException`, retaining their causes.
Existing `IocExtractorException` values are preserved; Errors are not contained.
The codec itself emits no diagnostic or log and performs no filesystem cleanup.

No byte-size/whole-document quota is added by this adapter: decode accepts a
complete byte array and materializes document/model lists; encode builds a
complete output array. Library parser constraints still apply. This is metadata
serialization, not a streaming CSV or hostile-input isolation boundary.

The codec does not read files, check paths against a root, recompute artifact
hashes, compare row counts to CSV contents or verify export-ledger identity.
Filesystem safety and hash/identity checks belong to
[SliceTreeVerifier](../../../../../../../../../../adapter-csv/src/main/java/com/iocextractor/adapter/out/sink/csv/SliceTreeVerifier.java);
row-count/content consistency is outside this codec.
The filesystem writer reports `EXPORT.MANIFEST_INVALID` with run/path context;
successful decoding alone is not a valid complete-slice receipt.

## Verification and related docs

The [test package](../../../../../../../../test/java/com/iocextractor/adapter/out/manifest/json/README.md) maps exact v1 bytes, v2 policy
mapping and rejection cases. [Immutable export](../../../../../../../../../../../docs/dev/artifact-export.md)
owns the cross-module integrity/recovery protocol; the
[CSV format capability](../../../../../../../../../../../docs/dev/csv-output-format.md) owns layout and
compatibility policy. Precise member/model contracts remain in Java Javadoc.
