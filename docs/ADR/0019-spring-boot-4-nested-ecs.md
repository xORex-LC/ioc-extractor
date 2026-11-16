> English translation of the historical Russian record. Decision/status/dates are preserved. [Russian original](ru/0019-spring-boot-4-nested-ecs.md) (relative link addresses rebased on relocation).

<a id="0019--spring-boot-4-baseline-и-nested-ecs-wire-format"></a>
# 0019 — Spring Boot 4 baseline and nested ECS wire format

<a id="статус"></a>
## Status

**Accepted and implemented on 2026-07-21.** For release `0.1.1`, the supported
framework line is Spring Boot `4.0.x`; current candidate baseline: `4.0.7`.

This ADR partially supersedes [0018](0018-typed-ecs-structured-logging.md) only
in three places:

- exact Spring Boot baseline changes from `3.4.13` to `4.0.x`;
- physical ECS JSON representation becomes nested instead of flat dotted-key;
- static `event.dataset` is added by a narrow bootstrap encoder adapter rather
  than formatter configuration.

Other ADR-0018 decisions remain: typed event-local fields, string-only ambient
MDC, `event.action`/`event.outcome`, collision semantics, redaction, generated
catalog and the OBS-5 seam.

<a id="контекст"></a>
## Context

<a id="framework-remediation"></a>
### Framework remediation

The dependency-scan baseline found issues in the older Spring/Tomcat line.
The project first moved to the latest `3.5.x` as the recommended migration
bridge, then to Boot 4. This separated Spring Framework 6.2/Boot 3.5 changes
from Boot 4 module/API changes.

The full reactor `verify`, golden E2E and subsequent CI runs on `4.0.7` are green.
Returning to `3.5.x` would require separate security disposition and repeating
all release gates; it is therefore not a more conservative change in an already
stabilized candidate.

<a id="ecs-representation-changed-in-boot-35"></a>
### ECS representation changed in Boot 3.5

Spring Boot 3.5 changed built-in ECS rendering: dotted logical paths now
materialize as nested JSON objects. The logical field contract stayed the same,
but the observable wire format changed:

```json
{"ecs.version":"8.11","event.action":"app_start","event.outcome":"success"}
```

became:

```json
{
  "ecs": {"version":"8.11"},
  "event": {"action":"app_start","outcome":"success"}
}
```

Thus the former consumer query `jq '.["event.action"]'` no longer reads the value;
its nested equivalent is `jq '.event.action'`.

`event.outcome` is not a new field in release `0.1.1`: it existed before.
Only its physical representation changed, together with other dotted paths.

<a id="static-dataset-collision"></a>
### Static dataset collision

After moving to nested representation, independently adding `event.dataset`
alongside existing `event.action`/`event.outcome` can create two top-level `event`
objects. Dataset must therefore enter the same context-pair stream before
standard ECS serialization.

<a id="решения"></a>
## Decisions

<a id="1-поддерживаемая-линия--spring-boot-40x"></a>
### 1. The supported line is Spring Boot 4.0.x

Release `0.1.1` stays on `4.0.x`, with candidate baseline `4.0.7`. The parent POM
version is authoritative. Before updating the Boot BOM, only explicit
parent-owned patch overrides for the already qualified dependency line are
allowed (currently Tomcat, Jackson 2 and Log4j 2); their presence and rationale
must remain visible in dependency management and security evidence.

Moving to `4.1.x` or another feature line during stabilization is forbidden.
It requires a separate post-release upgrade slice.

<a id="2-boot-35-остаётся-migration-bridge-а-не-supported-baseline"></a>
### 2. Boot 3.5 remains a migration bridge, not a supported baseline

Version `3.5.x` was used for incremental migration, not as a second supported
runtime line. The project qualifies and releases one framework baseline.

Returning to `3.5.x` is allowed only as plan B for a reproducible Boot 4 blocker
in exact-candidate smoke. Such rollback requires a new dependency scan, full
`verify`, packaging contract and stand smoke; prior Boot 4 evidence does not apply.

<a id="3-публичный-ecs-wire-format--nested-json"></a>
### 3. Public ECS wire format is nested JSON

Java catalog names and ECS vocabulary remain logical dotted paths
(`event.action`, `ecs.version`, `ioc.run.id`). Physical daemon JSON uses nested
objects (`event.action`, `ecs.version`, `ioc.run.id` are read as
`.event.action`, `.ecs.version`, `.ioc.run.id`).

This is an observable compatibility break for jq queries, ingest pipelines and
dashboards even when the field's logical name and JSON scalar type do not change.
Release notes must show before/after payloads and consumer-query migration.

<a id="4-encoder-остаётся-узким-bootstrap-adapter-ом"></a>
### 4. The encoder remains a narrow bootstrap adapter

`IocEcsStructuredLogEncoder` only adds static `event.dataset` to the same
key/value stream and delegates final serialization to the standard Spring Boot
ECS formatter. It does not become a general JSON library, own ECS schema or
enter domain/application.

The regression contract must check:

- one nested `event` object without duplicate keys;
- nested `ecs`, `service`, `event` and `ioc` paths;
- preserved number/boolean JSON scalar types;
- `event.action`, `event.outcome` and `event.dataset` in one object.

<a id="5-semver-disposition-для-011"></a>
### 5. SemVer disposition for 0.1.1

Nested representation and typed scalar migration are a public wire break.
Keep version `0.1.1` for the current pre-`1.0.0` release because the project
has declared no supported external ECS consumer and has no production
Elasticsearch integration. Explicitly disclose the exception in release notes.

If a supported consumer is found before the tag, reconsider: compatibility/
a transition window or release `0.2.0` is required.

<a id="6-exact-candidate-evidence-нельзя-наследовать-от-миграционных-коммитов"></a>
### 6. Exact-candidate evidence cannot be inherited from migration commits

Before tagging, check the actual built candidate with final version/config/
packaging inputs. Minimum stand contract:

1. clean `verify` and packaging contract;
2. start the packaged daemon with production-like logging configuration;
3. valid JSON and representative nested ECS assertions;
4. `/actuator/health`, `/actuator/info` and `ioc --version`;
5. graceful shutdown;
6. dependency security scan;
7. representative operator queries for new nested paths.

Evidence records commit, version, artifact digest, effective configuration and
date. Any subsequent change to these inputs invalidates the stand result.

<a id="следствия"></a>
## Consequences

- The project has one supported Spring Boot line, without a dual 3.5/4.0 matrix.
- Daemon ECS JSON consumers must migrate from bracket access to dotted keys
  to nested paths.
- ADR-0018 remains authoritative for typed logging semantics; this ADR owns the
  framework baseline and physical representation.
- Final stand smoke remains a release gate, not a historical argument for the
  selected version.
- The custom encoder is deliberately small and should be removed if standard
  Boot configuration can safely express the same dataset contract.

<a id="отклонённые-варианты"></a>
## Rejected alternatives

<a id="вернуться-на-boot-35-перед-релизом-только-ради-меньшего-номера"></a>
### Return to Boot 3.5 before release merely for a lower version number

Rejected without an actual Boot 4 blocker: this is a new migration, restoration
of security surface and full invalidation of existing build/security evidence.

<a id="поддерживать-одновременно-boot-35-и-boot-4"></a>
### Support Boot 3.5 and Boot 4 simultaneously

Rejected: doubles the runtime/test matrix without value for a small application
with one deployable artifact.

<a id="восстановить-flat-dotted-key-json-собственным-formatter-ом"></a>
### Restore flat dotted-key JSON with a custom formatter

Rejected: the project would own complete ECS serialization merely to preserve
a pre-release physical shape. Explicit consumer migration is cheaper and clearer.

<a id="связанные-документы-и-источники"></a>
## Related documents and sources

- [ADR-0018](0018-typed-ecs-structured-logging.md): typed logging semantics;
- [observability.md](../dev/observability.md): current runtime contract;
- [RELEASE-PROCESS.md](../RELEASE-PROCESS.md): release evidence and notes;
- [Spring Boot 3.5 Release Notes](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-3.5-Release-Notes);
- [Spring Boot 4.0 Migration Guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide);
- [Spring Boot structured logging reference](https://docs.spring.io/spring-boot/reference/features/logging.html#features.logging.structured).

<a id="дополнение-2026-07-21-retarget-релиза-на-020"></a>
## Addendum 2026-07-21: retarget release to 0.2.0

Before the final candidate and tag, the release was retargeted from `0.1.1`
to `0.2.0`. Rationale: the cumulative scope after `v0.1.0` — new storage,
export/sync and event-coordination capabilities, strict configuration contract,
diagnostics outcome contract and public ECS wire-format change — is a minor
release under the project's accepted SemVer policy, not a compatible patch set.

This addendum supersedes only section 5's version disposition: the final
candidate, release notes and evidence for this ADR concern `0.2.0`. The one
supported Spring Boot line `4.0.x`, nested ECS representation, plan B and all
other ADR decisions remain unchanged. Mentions of `0.1.1` above remain as
historical context before retargeting.
