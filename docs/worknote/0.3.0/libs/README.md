# Shared-library execution notes

This directory holds temporary release planning and historical exploration,
not the maintained public API documentation. The owner selected the shared
`r030-libraries` branch; dated interviews preserve older baselines and choices.

## Current state — 2026-11-01

Concurrency, diagnostics, logging, Spring Boot configuration and both local-events
artifacts have been extracted and published. Their maintained consumer guides
are available in [English](../../../guides/libraries/README.md) and
[Russian](../../../guides/ru/libraries/README.md). Each guide links module
references, package Javadoc, accepted ADRs, executable consumers and the permanent
EN/RU release record under [.github/release-notes](../../../../.github/release-notes/).

Completed per-library plans, draft ADRs and historical source-copy probes were
retired after promotion/archive. This does not erase original failed runs or
extend qualification to another deployed service/provider/framework version.
Configuration's protected Central CI recovery remains TOOL-8; the logging GPG
fixture follow-up TOOL-7 and strict-error containment OBS-6 remain separate
[known issues](../../../KNOWN-ISSUES.md). Existing coordinates and tags stay immutable.

The events documentation now separates [JDK contracts](../../../guides/libraries/events.md)
from the [Spring adapter](../../../guides/libraries/events-spring.md), with EN/RU
code parity and original examples. Both records preserve E0–E5 outcomes,
core-first publication, signed pair identities and actual Central/GitHub evidence.
This documentation work performs no publication or service release.

## Remaining notes

- [ETL assessment](lib-4-etl-analysis.md): existing runner capabilities, consumers,
  contract limits and reasons to defer extraction. ETL stays an internal module;
  no extraction is planned without a selected second consumer and reviewed API.
- [Historical interview](shared-library-exploration.md): earlier answers,
  alternatives and checkpoints. Published contracts are in the maintained guides
  and ADRs; dated proposed/unimplemented states here are not current status.

Promote a future durable decision into its guide, capability document, ADR,
release record or known-issues registry. Update incoming links before removing
another completed note. Do not recreate implementation plans as public references.
