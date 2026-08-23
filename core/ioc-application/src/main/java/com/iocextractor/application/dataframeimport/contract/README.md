# com.iocextractor.application.dataframeimport.contract

## Purpose

Compiles untrusted typed configuration into an immutable, reference-complete
source-contract catalog pinned by delivery fingerprints.

## Structure

| File | Purpose |
|---|---|
| `DataframeImportCatalogDraft.java` | Framework-free source, authority, recognition and mapping shape |
| `DataframeImportCatalogEnvironment.java` | Artifact/transform/endpoint reference catalog |
| `DataframeImportCatalogCompiler.java` | Collect-all semantic validation and deterministic fingerprinting |
| `DataframeImportCatalog.java` | Executable immutable catalog generation |
| `CompiledDataframeImportContract.java` | One validated versioned contract and fingerprint |
| `DataframeImportRecognizer.java` | Exact-one structural recognition inside one source allowlist |
| `DelimitedInputReadException.java` | Safe parser-boundary failure shared with input adapters |
| `ImportRecognitionException.java` | Safe critical recognition failure taxonomy |
| `ImportContractViolation.java` | Safe configuration violation |

## Dependencies

**Depends on:** `dataframeimport.model` and Java security primitives. **Must not
depend on:** Spring binding, Commons CSV, filesystem, SMB or persistence types.
