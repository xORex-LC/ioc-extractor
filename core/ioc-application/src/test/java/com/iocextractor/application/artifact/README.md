# Artifact identity and lifecycle tests

Local deterministic contracts for artifact keys, writes, fingerprints and forward projection recovery.

| Entry points / files | Responsibility |
|---|---|
| [CanonicalArtifactIdentityResolverTest](CanonicalArtifactIdentityResolverTest.java), [CanonicalArtifactKeyResolverTest](CanonicalArtifactKeyResolverTest.java) | Configured/versioned record and match identities, final-row normalization and collision material. |
| [CanonicalWriteResultTest](CanonicalWriteResultTest.java), [FingerprintFramingTest](FingerprintFramingTest.java), [IngestRunRecoveryServiceTest](IngestRunRecoveryServiceTest.java) | Public-update counters, stable framing and committed-run projection recovery/diagnostics. |
| [lifecycle/README.md](lifecycle/README.md) | Validity, activation/admission, receipts, generations and concurrency. |
| [policy/ArtifactOccurrenceSelectorTest](policy/ArtifactOccurrenceSelectorTest.java), [policy/LatestRegisteredValuePolicyTest](policy/LatestRegisteredValuePolicyTest.java) | Whole-candidate selection and ordered-field origin/public-change semantics. |

These tests assert application decisions with explicit/fake storage evidence. Field-order assertions distinguish public changes from origin-only advancement; they do not qualify a particular SQL implementation. Canonical row keys, reusable export slots and observation ranks remain separate contracts.

[Test map](../README.md) owns commands/evidence limits; [TESTING.md](../../../../../../../../../docs/TESTING.md) owns lifecycle and test quality.
