# Import contract compilation and recognition

## Purpose and boundary

Convert typed declarations into a reference-complete immutable catalog, then recognize exactly one contract allowed by a source. Spring binding and CSV decoding remain outside this package.

## Structure

| Entry points / files | Responsibility |
|---|---|
| [DataframeImportCatalogDraft](DataframeImportCatalogDraft.java), [DataframeImportCatalogEnvironment](DataframeImportCatalogEnvironment.java) | Declaration model and allowed artifact/transform/endpoint environment. |
| [DataframeImportCatalogCompiler](DataframeImportCatalogCompiler.java), [DataframeImportCatalog](DataframeImportCatalog.java), [CompiledDataframeImportContract](CompiledDataframeImportContract.java) | Collect violations, resolve policy and retain deterministic behavior fingerprints. |
| [DataframeImportRecognizer](DataframeImportRecognizer.java) | Header-only structural selection through the reader port. |
| [ImportContractViolation](ImportContractViolation.java), [DelimitedInputReadException](DelimitedInputReadException.java), [ImportRecognitionException](ImportRecognitionException.java) | Safe violations and stable parser/recognition reason taxonomy. |

## Contracts and maintenance

Structural recognition is neither filename guessing nor content-type conversion. No match and ambiguity reject; the source allowlist limits which contracts participate. Contract version/fingerprint must remain pinned across staging retries.

Compile provider/column/authority/route references before runtime use. Fingerprint content-affecting semantics without incorporating a delivery's runtime identity. Safe failure carriers carry stable reasons/positions, not source CSV values or arbitrary parser exception text.

[Capability internals](../../../../../../../../../../docs/dev/dataframe-import.md) owns the cross-module flow. [Module map](../../../../../../../../README.md) owns dependencies and verification; exact member guarantees remain in Javadoc.
