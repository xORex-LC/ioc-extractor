# R0 admission: contracts and bounded runtime experiment

Status: completed bounded admission, 2026-09-27, source HEAD `f43037ee87ef`.
Implementation decision: [ADR 0031](../../../ADR/0031-bounded-camel-preparation-runtime.md).
The user accepted the design and authorized R0. This record closes the joint
minimal R0/P0 interface agreement; it does not claim full P0 code-relocation audit,
R1 module admission or production integration.

## Minimal contracts for implementation

Names describe semantic contracts; production Java declarations are introduced
with their consumers in R1/P0 rather than adding unused public interfaces now.

| Contract | Required contents and invariants |
|---|---|
| Operation binding | Stable operation ID and semantic version; declared input/output type and parameter schema; pure invocation returning one typed outcome. No side effects, logging, mutable singleton input state or framework types. |
| Operation outcome | Available non-null typed value with provenance, absent optional input, or expected failure reference. Failure has producer ID, reason code and invocation-local identity; no severity or persistence decision. Unexpected exceptions are not converted to expected failures. |
| View binding | Stable view ID, producer/input dependencies and output type; original is immutable processing input, not raw source bytes. Recovery has one explicit alternate, allowlisted reasons and actual alternate type/provenance. |
| Admitted plan | Immutable ordered views, conditions, branch/destination bindings, no-match action, structural limits and policy identity. Defensive copies; no mutable Spring properties or Camel objects. Type and authority checks complete before activation. |
| Invocation | One input occurrence or logical row, pinned plan, source position/correlation and local view/outcome state. At-most-once lazy view evaluation; absence, failure and not-yet-evaluated are distinct. Release all state when invocation ends. |
| Preparation result | Typed candidate results plus bounded causal/selection evidence. No IDs reserved, no canonical writes, no emitted diagnostics. Finalization bridge resolves consumer recovery and hands final diagnostics to the existing boundary. |

Document input retains attributed occurrence/rank metadata. Structured import
input retains named cells and ABSENT/NULL/VALUE, requested slots and source
authority in its application context; do not flatten it into a single IOC value.
One candidate per branch/input is the maximum. Final record/match keys and winner
selection stay application-owned after output validation.

## Module decision

Admit option B. R1 adds `adapter-processing-camel`, with neutral technical packages
and an inward-facing application bridge. Compile-time package tests must forbid
neutral packages from importing the bridge, IOC or application. Camel types stay
inside the adapter; bootstrap wires lifecycle/registries. This is enforced internal
separation, not a claim that the adapter artifact is IOC-independent.

No separate JDK routing executor is introduced. `ioc-processing` remains the
pure semantic module candidate, subject to P0 import closure. `platform-etl`,
diagnostics and existing canonical/recovery owners remain authoritative.

## Initial structural limits and qualification sizes

Initial compiler admission limits for R1: 32 plans, 64 views and 64 branches per
plan, 256 total condition nodes per plan, maximum condition nesting 16 and ID
length 64. The recovery rules forbid cycles and chained recovery nodes. These are
conservative implementation limits, not measured throughput guarantees; reject
excess at startup with a location, and review changes explicitly. Keep the current
diagnostic retention budget separate from graph limits.

R5 workload: 1,000 inputs for correctness smoke and 100,000 for measured comparison,
with 1/4/16 selected branches, success/failure/recovery mixtures and 1/4 concurrent
callers. Record actual heap/startup/allocation/throughput before selecting an
acceptance budget; do not claim those experiments ran in R0.

## Executed experiment

Source: [isolated probe](qualification/r0/README.md). It intentionally is not a
reactor module or production implementation. Maven test and verbose dependency
tree resolved Camel 4.22.1 under Boot 4.0.8 management on Java 21.

Two JUnit tests cover successful Spring-owned startup and shutdown, synchronous
direct execution on the caller thread, original operation exception propagation,
exactly one attempt per call (no redelivery), and invalid endpoint startup failure.
The probe uses core-engine, direct and core-languages; no Camel Spring starter.
The resolved family includes Camel 4.22.1, Spring 7.0.9 and SLF4J 2.0.18.

This proves the selected minimal embedding only. It does not exercise the full
application dependency graph, production readiness orchestration, security scan,
concurrent shutdown or all route EIPs. Those remain R1–R5 evidence. No dependency
or analyzer scope in the production reactor was changed. Historical reactor
verify/PMD reports are not reported as fresh for this probe.
