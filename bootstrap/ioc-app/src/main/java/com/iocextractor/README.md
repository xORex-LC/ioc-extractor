# Application entry package

## Purpose and structure

This source directory contains only
[IocExtractorApplication](IocExtractorApplication.java) and the
[bootstrap package](bootstrap/README.md). Domain, application, adapters and
platform packages with the same `com.iocextractor` prefix live in other Maven
modules; they are not child source directories here. The
[module README](../../../../../README.md) owns dependency and packaging rules.

## Process entry and ownership

`main` evaluates these paths in order:

| Path | Owner and effect |
|---|---|
| Internal `--ioc.validate-yaml=...` request | `IocYamlSyntaxCheck`: syntax-only check before creating a Spring context |
| Internal `--ioc.validate-config=...` request | `IocSemanticConfigurationCheck`: short-lived explicit configuration/preflight context, no application component scan |
| Lightweight CLI paths | `EarlyCliLauncher`: help/version, daemon health client and syntax handling before application startup |
| Remaining command/runtime work | `SpringApplication.run`, then the CLI adapter in oneshot or daemon components |

The first handled path returns the process exit code. Following normal Spring
startup, oneshot uses `SpringApplication.exit(context)` before process exit;
daemon mode stays alive. `main` belongs to process execution: tests needing a
context use Spring test facilities or fork a JVM rather than invoking its
`System.exit` paths inside the test JVM.

Configuration scanning and component scanning assemble the deployed graph from
the selected reactor modules. Main does not implement extraction, persistence
or transport behavior itself. Business results/CLI exit mapping remain in their
own use cases and driving adapter.

The semantic check keeps effective Boot sources/imports/profiles but blocks extra
bean-source injection and caller-owned CLI config-loading routes. Its console
suppression is a command boundary, not an embeddable logging-isolation API.
YAML syntax alone does not prove binding or semantic validity; a semantic pass
does not prove storage access, credentials or deployment readiness.

## Runtime and verification

`DaemonWebEnvironmentPostProcessor` runs after ConfigData loading. Daemon forces
servlet mode and eager singletons; ordinary oneshot uses lazy initialization and
the classpath non-web default. The checker pins its own non-web short lifecycle.
These settings do not replace recovery-before-intake coordination.

[CLI adapter](../../../../../../../adapters/adapter-cli-picocli/README.md) owns parsing and early commands.
[Runtime resources](../../../resources/README.md) owns defaults and registration;
[tests](../../../../test/java/com/iocextractor/README.md) cover lazy contexts,
configuration-only checks, build identity and actual composed scenarios.
