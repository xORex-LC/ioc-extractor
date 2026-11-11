# Inbound CSV tests

## Purpose and structure

Contracts for [strict CSV input](../../../../../../../main/java/com/iocextractor/adapter/in/csv/README.md). All three suites use
Surefire; source bytes/files and schemas are synthetic and local to each test.
Files are created under JUnit temporary directories rather than a shared resource
tree. No live inbox, database or SMB endpoint is required.

| Suite | Contract |
|---|---|
| [CommonsCsvDelimitedRecordReaderTest.java](CommonsCsvDelimitedRecordReaderTest.java) | Source order/logical numbering, aliases/exact headers, blank-line behavior, malformed encoding/separators, row/column/field/record limits, header-only probes and immediate callback-failure propagation |
| [DelimitedInputLimitingReaderTest.java](DelimitedInputLimitingReaderTest.java) | Quote-aware early counters: embedded delimiters/newlines, escaped quotes, field resets and record bounds |
| [CsvImportValueValidatorRegistryTest.java](CsvImportValueValidatorRegistryTest.java) | Whole-cell carrier kinds, shared structural predicates, network syntax and algorithm-specific hashes, canonical signed integers and invalid registry references |

## Assertions and scope

Preserve independent synthetic byte strings and exact expected parsed cells or
stable failure reasons. A header probe is not full-file acceptance; a recognized
network category is not structural address validity. Delimiter-only rows remain
observable to row policy while short rows fail structural validation.

Validator tests use the shared refang/extraction/classification collaborators;
matching success does not assert that imported cells have been rewritten. Integer
boundaries protect identity from storage normalization. These suites do not
qualify contract recognition across a catalog, snapshot transport ownership,
workspace quotas, canonical promotion or full managed-import recovery.

## Verification

Run from the repository root:

```bash
make test-module MODULE=adapters/adapter-csv
```

Application orchestration and broader examples belong to
[managed import](../../../../../../../../../../docs/dev/dataframe-import.md); lifecycle and assertion
policy belongs to [TESTING.md](../../../../../../../../../../docs/TESTING.md).
