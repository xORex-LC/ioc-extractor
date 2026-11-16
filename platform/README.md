# Platform modules

<a id="platform"></a>
<a id="назначение"></a>
<a id="структура"></a>
<a id="зависимости"></a>

## Purpose and boundary

This directory contains generic cross-cutting libraries and IOC-owned support
modules. Directory placement alone does not make a module reusable or published:
IOC diagnostic/logging catalogs and rendering remain service-owned.

Platform modules depend on JDK, their declared external APIs and other platform
modules, never core, adapters or bootstrap. A bridge depends on the platform
contracts it connects. Production and test dependency scopes are distinct;
module POMs and local READMEs provide the exact closure.

## Modules

| Module | Responsibility and publication boundary |
|---|---|
| [platform-errors](platform-errors/README.md) | Internal unchecked exception base; still used, despite its legacy common package |
| [platform-diagnostics-api](platform-diagnostics-api/README.md) | Published JDK-only diagnostic values, policies, counts, bounded collectors and sinks |
| [platform-diagnostics](platform-diagnostics/README.md) | Internal IOC catalogs, context metadata, typed exception and rendering |
| [platform-etl](platform-etl/README.md) | Internal Pipes-and-Filters runner, envelope and observer contracts |
| [platform-events](platform-events/README.md) | Published JDK-only event metadata and publish-only contract; no broker |
| [platform-concurrency](platform-concurrency/README.md) | Published JDK-only keyed execution and exclusion primitives |
| [platform-logging-slf4j](platform-logging-slf4j/README.md) | Published SLF4J records, restoring MDC, scalar ECS descriptors and explicit masking |
| [platform-observability](platform-observability/README.md) | Internal IOC action/field catalogs, logging wrappers and ETL observer |
| [platform-diagnostics-logging](platform-diagnostics-logging/README.md) | Internal diagnostic-to-operational-logging bridge |

## Entry points and verification

Use the [library catalog](../docs/guides/libraries/README.md) to choose a published
API, its required dependencies and qualified examples. Configuration and Spring
events libraries live under adapters, so this directory is not the full catalog.
Independent versions are accepted policy; Maven/CI activation remains TOOL-9.

Module READMEs own local maps and checks. [Observability](../docs/dev/observability.md)
explains IOC integration; [boundaries](../docs/BOUNDARIES.md) owns dependency rules.
