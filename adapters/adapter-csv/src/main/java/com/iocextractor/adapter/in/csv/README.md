# com.iocextractor.adapter.in.csv

## Purpose and boundary

Strict CSV input behind application import ports. The caller supplies dialect,
charset, limits and recognition rules, and resolves an opaque snapshot reference.
This package does not fetch deliveries, claim transport ownership, stage database
mutations or choose canonical winners. Follow the [module boundary](../../../../../../../../README.md).

## Structure

| File | Responsibility |
|---|---|
| [CommonsCsvDelimitedRecordReader.java](CommonsCsvDelimitedRecordReader.java) | Header probe and callback-streaming `DelimitedRecordReader` |
| [RecordSeparatorValidatingReader.java](RecordSeparatorValidatingReader.java) | Internal physical LF/CRLF policy validation before tokenization |
| [DelimitedInputLimitingReader.java](DelimitedInputLimitingReader.java) | Internal quote-aware field/record counters before token allocation |
| [DelimitedRecordReadException.java](DelimitedRecordReadException.java) | Adapter exception carrying the application input-failure reason |
| [ImportSnapshotPathResolver.java](ImportSnapshotPathResolver.java) | Reference-to-local-path seam; snapshot ownership stays with its owner |
| [CommonsCsvImportValueTransformRegistry.java](CommonsCsvImportValueTransformRegistry.java) | Import-port bridge to the configured processing transform family |
| [CsvImportValueValidatorRegistry.java](CsvImportValueValidatorRegistry.java) | Opt-in whole-cell IOC and canonical-integer validation |
| [package-info.java](package-info.java) | Package boundary |

## Input flow and ownership

The reader opens/closes its decoder, separator validator, limiting reader and
Commons CSV parser. Decoder errors use `REPORT`; malformed/unmappable bytes are
not repaired. Separator policy validates physical line breaks, including those
inside quoted fields. The resolver must provide the correct stable snapshot;
opening/closing a stream does not transfer ownership of that snapshot.

`readHeader` probes the raw header for recognition; it does not validate the
entire payload. Full `read` compiles the shared application `ImportHeaderPlan`,
resolving aliases before duplicate detection and requiring exact configured
header membership. Header failures report structure/counts rather than echoing
external header values. The adapter does not maintain a competing header matcher.

Rows are passed synchronously in file order, without collecting the input.
The first data row has logical CSV record number `2` (header is `1`); a logical
record can span physical lines. Consumer failure stops delivery and closes the
reader stack. The caller owns any downstream retention or side effects.

Empty physical lines are ignored, including trailing lines. Delimiter-only and
whitespace-only records are not silently dropped. Short/nonmatching-width rows
fail structural checks; row-level blank-cell policy belongs to the contract.
Surrounding spaces are preserved by the parser.

## Limits and failures

Header/data column, row, field and record limits fail closed. Decoded Java
character limits are enforced before Commons CSV tokenization by a quote-aware
reader, then checked again against parsed headers/records. The early record
counter also sees CSV syntax; do not reinterpret it as only the sum of cell
payload lengths. Limits do not provide a global heap quota for consumers.

Stable failure reasons distinguish unsupported charset, malformed encoding,
separator mismatch, header mismatch, column-count mismatch, resource limits and
parser failure. Unexpected runtime failures remain failures; this adapter does
not turn callback/programming defects into rejected data rows. Cause chains may
contain lower-level exception detail, so callers must preserve their reporting
and disclosure policy rather than printing arbitrary causes as safe diagnostics.

## Import registries

`CommonsCsvImportValueTransformRegistry` snapshots the configured transform map,
splits a `name:argument` specification and delegates to the shared transform.
Expected `MappingValueException` becomes `ImportValueMappingException`; unknown
compiled references and unexpected defects are not downgraded to data failures.

`CsvImportValueValidatorRegistry` refangs a temporary view and requires one
whole-cell indicator with only blank surrounding text. Network values also pass
`NetworkAddressParser`; a recognized category alone does not prove valid IP,
host or port syntax. Validation does not rewrite the supplied cell.

The rule catalog includes `bare-ip`, `url-address`, `clean-domain`, `hash`,
`network-address`, algorithm-specific `md5`/`sha1`/`sha256`, and
`canonical-integer`. The integer rule requires the exact `Long.toString` form
within signed 64-bit bounds; noncanonical spellings must not drift when stored
as INTEGER keys. Rules are opt-in per contract column. `ruleKeys()` supplies
preflight's supported catalog and `semanticRevision()` identifies these semantics.

## Verification and related docs

The [test package](../../../../../../../test/java/com/iocextractor/adapter/in/csv/README.md) maps parser and validator contracts.
Refer to [managed import](../../../../../../../../../../docs/dev/dataframe-import.md) for recognition,
cell presence, mapping, identity and delivery orchestration. Exact member
contracts remain in the public types' Javadoc and application port definitions.
