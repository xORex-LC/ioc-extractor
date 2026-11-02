# [Capability name]

[Русская версия](ru/template.md)

<!--
Copy this template for one developer-facing capability guide.
Remove this comment and the template's language-navigation line before publishing;
add links to the actual English/Russian document pair instead.

Keep durable knowledge: responsibility, runtime flow, model/invariants, effects,
failure/recovery, extension path, live sources and update triggers.
Select the sections relevant to the capability; do not invent a state machine.

Do not copy complete class/method inventories, library versions, full configuration,
implementation milestones or facts already maintained by a generator.
The document is a map for reading and changing code, not a prose copy of it.
-->

In one paragraph, explain the capability, its input/result and its boundary.
Name relevant responsibilities owned elsewhere.

## Runtime flow

Show the main data/control flow. Mark durable or side-effect boundaries when
they affect correctness; use the actual phases, including a checkpoint if needed.

```text
[input] -> [decision] -> [commit] -> [output]
```

## Boundaries

Identify participating layers/modules, the main driving/driven ports and the
technology hidden by an adapter. State who owns authoritative data and coordination.

Link to module/package `README.md` for local structure and dependency rules, and
to Javadoc for precise type/operation contracts. Retain the key relationships
needed to understand this capability without listing every implementation class.

## Model and invariants

Explain non-obvious values/structures, identity, ordering, units and boundary
conditions that affect correctness. Describe ownership, lifecycle, concurrency
and resource bounds where relevant. Keep exact member contracts with their code.

1. **[Invariant].** Why it matters, where it is enforced and which test protects it.

## Failures and recovery

Describe meaningful partial outcomes and failure/recovery behavior. Name what
has already happened when a failure is reported, who retries, and what makes a
retry safe. If no durable state machine exists, do not introduce one in prose.

| Situation | Behavior | Correctness authority |
|---|---|---|
| [Failure or interruption] | [Outcome and recovery] | [Transaction / ledger / retry / operator action] |

## How to extend

Give a short route for a typical change: the contract to extend, the adapter or
declarative rule to add, and the tests to update. Distinguish capability policy
from reusable mechanisms and service composition.

## Sources of truth

Provide a small set of concrete, linked entry points sufficient to verify the
mechanism, usually:

- Production assembly or primary use case.
- Public port or state model.
- Authoritative configuration.
- Contract/TCK/integration test.
- Generated reference, if one exists.

Replace these categories with actual relative links. Follow
[CONVENTIONS](../CONVENTIONS.md) for documentation ownership and style.

## When to update this document

Name changes in behavior or responsibility, such as:

- Commit/side-effect boundaries or partial outcomes.
- State model, invariants, resource ownership or concurrency guarantees.
- Recovery/retry rules or the owner of authoritative data.
- A newly supported extension contract.

A class rename requires fixing links; rewrite behavior only when its meaning changes.

## Related documents

Link to decisions in ADRs, root maps/policies, operator guides and co-located
module/directory `README.md`. Include each only when it helps the reader's next step.
