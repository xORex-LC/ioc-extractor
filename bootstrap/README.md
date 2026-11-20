# Bootstrap

<a id="назначение"></a>
<a id="структура"></a>
<a id="зависимости"></a>

## Purpose and boundary

Runtime assembly and application lifecycle. The only Maven module here is
[ioc-app](ioc-app/README.md), the repository's only executable Spring Boot JAR.
Other reactor JARs are libraries or internal components, not standalone services.

## Structure

| Path | Responsibility |
|---|---|
| [ioc-app](ioc-app/README.md) | Entry point, typed configuration, composition, command-specific graphs, daemon schedulers, health and logging backend |

## Dependencies

Bootstrap depends on the core, platform and adapters needed to assemble the
application. Inner modules never depend on bootstrap. Business policy belongs
in core; SQL, parsing, transport and mapping mechanisms belong in their adapters.
Bootstrap translates configuration into those contracts and owns runtime wiring.

## Entry points and verification

Start with the module README for composition packages, resources and test maps.
[Architecture](../docs/ARCHITECTURE.md) explains runtime flows;
[configuration](../docs/dev/configuration.md) explains startup and preflight;
[deployment](../docs/guides/deployment.md) explains the runnable distribution.
Run application/module or complete reactor checks through [tools](../tools/README.md).
