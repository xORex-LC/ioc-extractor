# Driven application contracts

## Purpose and boundary

Capabilities required by application orchestration, implemented or supplied outward. This namespace includes narrow operation interfaces and their typed request/result carriers.

## Structure

| Entry points / files | Responsibility |
|---|---|
| [SourceReader](SourceReader.java) | Decoded text boundary; streaming overload is required by production document processing. |
| [artifact/README.md](artifact/README.md), [ingest/README.md](ingest/README.md), [observation/README.md](observation/README.md) | Workspace/canonical data, whole-source journals and dataframe registration. |
| [dataframeimport/README.md](dataframeimport/README.md), [export/README.md](export/README.md), [sync/README.md](sync/README.md) | Structured import IO, immutable snapshots/slices and remote transport/ledgers. |
| [observability/README.md](observability/README.md), [maintenance/README.md](maintenance/README.md) | Computed item trace and ordinary retention IO. |

## Contracts and maintenance

`SourceReader.readText(Path, Writer)` leaves writer close with its caller. The default overload rejects unsupported streaming; implementing only the materialized convenience method is insufficient for the document path. Stopping parser failures must remain typed diagnostics instead of leaking SDK exceptions.

Adapter resource and atomicity promises vary by port. Keep driver-specific connections, HTTP/SMB types, Spring transactions and encoder objects out of these contracts. Application-owned value types/commands are expected; “driven ports” does not mean a directory may contain interfaces only.
[Module map](../../../../../../../../README.md) owns dependencies and verification; exact member guarantees remain in Javadoc.
