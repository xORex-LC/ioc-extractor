# Application ports

## Purpose and boundary

Separate external commands from capabilities the application requires. Bootstrap supplies adapter implementations; core never discovers them through a service locator.

## Structure

| Entry points / files | Responsibility |
|---|---|
| [in/README.md](in/README.md) | Driving use cases and command/result values. |
| [out/README.md](out/README.md) | Driven effects, stores, readers, observers and resource-owning callbacks. |

## Contracts and maintenance

Ports can carry typed commands/results and JDK values, including `Path`, without exposing SDK sessions or SQL handles. Cross-port orchestration belongs in application services. Precise transaction, replay, cursor/stream and side-effect guarantees belong to the relevant interface Javadoc, not a blanket claim that every call is pure or thread-safe.

Storage adapters implement driven contracts; driving adapters invoke use cases and keep exit/HTTP/daemon scheduling semantics outside. Stateful use cases can require an admission wrapper supplied by bootstrap. See the submaps before extending a port or adding a parallel boundary.
[Module map](../../../../../../../README.md) owns dependencies and verification; exact member guarantees remain in Javadoc.
