# Adapters

<a id="comiocextractoradapter"></a>
<a id="назначение"></a>
<a id="структура"></a>
<a id="зависимости"></a>

## Purpose and boundary

Adapters implement inbound or outbound ports at external integration boundaries.
Bootstrap composes them with the framework-free core. Each module owns one
coherent integration family; several cooperating libraries may belong to that
family. Reusable configuration and event adapters have separate publication
contracts; the remaining adapters are internal service modules.

Core never imports adapters. Adapter modules do not depend on sibling adapters;
bootstrap supplies collaborators through inward-facing contracts. Maven POMs,
Enforcer and ArchUnit own the executable dependency rules; see
[boundaries](../docs/BOUNDARIES.md).

## Modules

| Module | Responsibility |
|---|---|
| [adapter-cli-picocli](adapter-cli-picocli/README.md) | Driving CLI: extract, export, sync, managed import and health |
| [adapter-config-spring-boot](adapter-config-spring-boot/README.md) | Reusable name/declaration inspection; source loading, schema and enforcement stay service-owned |
| [adapter-events-spring](adapter-events-spring/README.md) | Explicit Spring event publication; listeners, multicaster and recovery stay service-owned |
| [adapter-regex-re2j](adapter-regex-re2j/README.md) | RE2/J and JDK implementations of the domain PatternEngine port |
| [adapter-psl](adapter-psl/README.md) | Guava Public Suffix List host classification |
| [adapter-source-tika](adapter-source-tika/README.md) | SourceReader document parsing, streaming HTML and parser admission |
| [adapter-csv](adapter-csv/README.md) | Strict import parsing, row preparation, mutable projections and immutable CSV exports |
| [adapter-processing-camel](adapter-processing-camel/README.md) | Embedded bounded plan execution, required by document and processed-import routing through bootstrap |
| [adapter-manifest-json-jackson](adapter-manifest-json-jackson/README.md) | Deterministic versioned manifest serialization and parsing |
| [adapter-store-jdbc](adapter-store-jdbc/README.md) | SQLite canonical/lifecycle state, migrations, projections and durable coordination ledgers |
| [adapter-transport-smb](adapter-transport-smb/README.md) | Remote fetch/publish, managed-import transport and optional change notifications |
| [adapter-ingest](adapter-ingest/README.md) | Spring Integration inbox polling, file ownership and daemon intake |

## Dependencies and entry points

Most adapters consume application/domain/platform contracts. The technical Camel
module is deliberately IOC-independent; bootstrap connects its neutral plan
contracts to IOC behavior. Integration types stay outside core. Follow each
module README for packages, ports, dependency scopes, ownership and tests.

[Architecture](../docs/ARCHITECTURE.md) and
[modularization](../docs/MODULARIZATION.md) describe cross-module relationships;
[processing routes](../docs/guides/ioc-processing-routes.md) describes operator plans.
