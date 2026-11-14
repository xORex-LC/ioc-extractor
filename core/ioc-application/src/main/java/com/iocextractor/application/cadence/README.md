# Clock-injected cadence policies

## Purpose and boundary

Pure interval and quiet-period decisions over caller-provided durable activity/checkpoint facts; no scheduler or worker ownership.

## Structure

| Entry points / files | Responsibility |
|---|---|
| [CadenceSource](CadenceSource.java), [CadenceSources](CadenceSources.java) | Decision interface and supported policy registry. |
| [IntervalCadenceSource](IntervalCadenceSource.java), [QuietPeriodCadenceSource](QuietPeriodCadenceSource.java) | Processing-time interval versus activity debounce with bounded pending latency. |

## Contracts and maintenance

Each independent workload owns its cadence state. Quiet-period tracks the first pending activity and max-cap so continuous changes cannot postpone work forever. The caller owns actual attempt/success acknowledgement and storage progress; failed work must not be reported as a successful checkpoint.

These policies do not discover canonical changes or guarantee wall-clock execution under blocked workers. Bootstrap owns scheduling/backoff, while export plan/revision policy can bypass ordinary cadence for initial or changed-plan formation.

[Capability internals](../../../../../../../../../docs/dev/artifact-export.md) owns the cross-module flow. [Module map](../../../../../../../README.md) owns dependencies and verification; exact member guarantees remain in Javadoc.
