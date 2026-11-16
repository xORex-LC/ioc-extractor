# External rule-data extension point

<a id="rules"></a>
<a id="назначение"></a>
<a id="структура"></a>
<a id="заметки"></a>

## Purpose and boundary

Reserved for future versioned rule files: indicator patterns, ordered refang
replacements and classification policies. This directory currently contains
only this README and is not a runtime configuration source.

## Current ownership

Rules are declared under `ioc.patterns`, `ioc.refang.rules` and `ioc.classify` in
[packaged defaults](../bootstrap/ioc-app/src/main/resources/application.yml),
with normal operator overrides. Creating a file such as `ioc-patterns.yml` here
does not load it automatically.

Externalizing rules requires an explicit loader, precedence and validation
contract. File-format knowledge belongs outside the domain; keep domain rules
and inward dependencies intact. Regex declarations must remain RE2-compatible.
Use [configuration](../docs/guides/configuration.md) for supported settings and
[processing](../docs/dev/processing.md) for execution semantics.
