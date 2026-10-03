# ADR 0032: Invocation-owned IOC semantic reuse

- Status: Accepted
- Date: 2026-10-03
- Related: [ADR 0031](0031-bounded-camel-preparation-runtime.md),
  [processing](../dev/processing.md)

## Context

Selected document processing must visit every attributed occurrence before
final-key selection. Repeated classification and host derivation are expensive,
but skipping repeated observations would lose source attribution, ordered field
positions, mapper diagnostics and import receipt participants. MatchPolicy
accepts a complete Indicator and has no value-only dependency guarantee.

## Decision

Application exposes separate DocumentProcessingPlan and DocumentProcessingSession
ports. The preparation stage owns one session, closes it before returning to the
failure checkpoint, and also closes it on an exception. Existing functional plans
receive an uncached forwarding adaptation. Bootstrap binds the handle to the pure
IndicatorProcessingSession collaborator in ioc-processing; Camel remains unaware
of its cache keys and ownership.

Classification entries use the complete Indicator (value, type, source label and
section) under a fixed policy instance. Original and derived classification can
share entries only when their classifier wrappers reference the same policy
object. An operation using a different policy computes without consulting that
cache. The policy's semantics must remain stable during the invocation; no cache
outlives the configuration/registry pin.

Host entries retain successful host value/type pairs keyed by original value/type.
The existing NetworkHostDeriver computes them; each reuse reconstructs the
Indicator with the current source. Expected derivation failures and unexpected
exceptions are not cached. Positions, ordinal, preparers, clock-based mapping,
consumers, diagnostics, replies and prepared candidates are never semantic entries.

Both caches share admission limits: 256 entries, 1 MiB charged retention and
64 KiB maximum per entry. Charge accounts for key/result text using UTF-16 size,
list elements and conservative object allowances; shared references are charged
repeatedly. These are admission budgets, not an exact JVM heap-size guarantee.
Exhaustion or oversized entries fall back to normal computation, without eviction
or changing acceptance. Sessions are thread-confined; there is no global cache,
ThreadLocal or new cache dependency/operator setting.

Processed import owns a new session within each logical row and closes it before
returning its assembly result. Delivery-wide reuse is deferred: it would require
an explicit staging-attempt handle through preparation and source-authority
validation. Receipt-only recovery opens no processing session. Every CSV row
still reaches staging; COALESCE and its warnings remain canonical import concerns.

## Consequences

Candidate retention in the two existing document reduction paths is proportional
to distinct final keys. Semantic state is admission-bounded per invocation, but
input and diagnostics remain input-proportional and concurrent sessions multiply
that allowance. Legacy KEEP_FIRST preparation remains ungrouped.

A calibration with 8,000 observations, 2,000-character values and one/four callers
fills the size budget before the entry budget; 40,000-character values bypass it.
The 20-key repeated fixture uses 40 entries. This supports conservative initial
limits, not a claim about complete pipeline performance or a customer resource
budget. Regression tests and separate application comparisons own those claims.
