# Managed import driving ports

## Purpose and boundary

Expose validation, candidate admission, ordered processing, recovery, status, replay and retention without granting direct ledger mutation.

## Structure

| Entry points / files | Responsibility |
|---|---|
| [ValidateDataframeImportUseCase](ValidateDataframeImportUseCase.java), [ValidateDataframeImportCommand](ValidateDataframeImportCommand.java), [ValidateDataframeImportResult](ValidateDataframeImportResult.java) | Advisory preview over caller-owned bytes; no reservation/write permission. |
| [AdmitDataframeImportUseCase](AdmitDataframeImportUseCase.java), [AdmitDataframeImportCommand](AdmitDataframeImportCommand.java), [AdmitDataframeImportResult](AdmitDataframeImportResult.java) | Reserve occurrence/order and begin ownership. |
| [ProcessNextDataframeImportUseCase](ProcessNextDataframeImportUseCase.java), [ProcessNextDataframeImportResult](ProcessNextDataframeImportResult.java), [RecoverDataframeImportsUseCase](RecoverDataframeImportsUseCase.java), [RecoverDataframeImportsResult](RecoverDataframeImportsResult.java) | Advance/recover the durable head; no caller-selected reorder/skip. |
| [QueryDataframeImportStatusUseCase](QueryDataframeImportStatusUseCase.java), [ReplayDataframeImportUseCase](ReplayDataframeImportUseCase.java), [ReplayDataframeImportCommand](ReplayDataframeImportCommand.java), [ReplayDataframeImportResult](ReplayDataframeImportResult.java), [RunDataframeImportRetentionUseCase](RunDataframeImportRetentionUseCase.java) | Bounded read model, new causal replay and terminal cleanup. |

## Contracts and maintenance

Validation does not pin a future producer delivery or guarantee the same live result. Live admission still requires source authority/readiness and exact immutable bytes. Processing selects the ledger-authoritative global head, even when triggered by an event.

Replay creates a new source-detached occurrence from protected terminal evidence; terminal records are not reopened and transport source disposition must not run again. Status/retention are bounded capability operations. Credential/config/startup/CLI policies stay outward.

[Capability internals](../../../../../../../../../../../docs/dev/dataframe-import.md) owns the cross-module flow. [Module map](../../../../../../../../../README.md) owns dependencies and verification; exact member guarantees remain in Javadoc.
