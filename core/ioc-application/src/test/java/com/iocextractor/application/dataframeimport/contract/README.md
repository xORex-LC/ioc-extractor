# Import contract tests

Independent valid/invalid declarations and header recognition scenarios.

| Entry points / files | Responsibility |
|---|---|
| [DataframeImportCatalogCompilerTest](DataframeImportCatalogCompilerTest.java), [DataframeImportCatalogCompilerValidationTest](DataframeImportCatalogCompilerValidationTest.java) | Collect-all validation, allowed references, policy/limit/format combinations and stable fingerprints. |
| [DataframeImportRecognizerTest](DataframeImportRecognizerTest.java), [ProcessedRouteRequirementTest](ProcessedRouteRequirementTest.java) | Exact-one source-allowlisted recognition and mandatory explicit processed route. |

Inputs are synthetic typed drafts and reader evidence. They test the compiler/recognizer contract, not Spring Binder precedence, concrete CSV byte decoding or producer source readiness. Those integration seams live in bootstrap/CSV/source adapters.

[Test map](../../README.md) owns commands/evidence limits; [TESTING.md](../../../../../../../../../../docs/TESTING.md) owns lifecycle and test quality.
