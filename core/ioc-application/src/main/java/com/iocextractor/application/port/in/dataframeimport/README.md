# com.iocextractor.application.port.in.dataframeimport

## Purpose

Driving ports for admission, advisory validation, ordered processing, recovery,
status and replay of managed dataframe import deliveries.

## Structure

| Port | Responsibility |
|---|---|
| `AdmitDataframeImportUseCase` | Reserve durable claim order before transport completion |
| `ValidateDataframeImportUseCase` | Side-effect-free advisory validation/preview |
| `ProcessNextDataframeImportUseCase` | Advance only the minimum nonterminal sequence |
| `RecoverDataframeImportsUseCase` | Reconcile durable evidence forward |
| `QueryDataframeImportStatusUseCase` | Return safe bounded aggregate status |
| `ReplayDataframeImportUseCase` | Create a new occurrence from terminal protected evidence |

## Dependencies

**Depends on:** framework-free dataframe-import values. **Implemented by:**
application services in later slices. Driving adapters depend on these ports,
never on concrete services.
