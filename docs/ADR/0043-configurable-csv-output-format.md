# ADR 0043: Configurable CSV record endings and independent quoting

- Status: Accepted
- Date: 2026-10-14
- Scope: Mutable CSV projections, immutable export format and manifest compatibility.

## Context

CSV output fixes CRLF and quotes every non-null field. Consumers also need LF
and independent policies for column names and data. A strict request for no
quotes cannot be implemented by silently escaping delimiters or emitting broken
records. Format changes must invalidate the export pre-gate and remain explicit
in saved recovery evidence.

## Decision

Add `record-separator`, `header-quoting` and `value-quoting` under `ioc.sink.csv`.
Use closed LF/CRLF and ALL/MINIMAL/NONE policies. Preserve CRLF/ALL/ALL defaults
and exact existing v1 output. Both CSV writers use one adapter formatter.
Mutable projections preserve charset replacement for ALL/ALL; MINIMAL or NONE
requires lossless encoding so replacement cannot inject unquoted CSV syntax.
Immutable slices continue to use strict encoding in every mode.

ALL quotes non-null fields, MINIMAL quotes when needed, and NONE rejects cells
that need quotes or escaping. The unquoted null marker remains reserved for
absence. MINIMAL quotes literal text equal to that marker; NONE rejects such
data rather than silently losing the distinction. Null markers containing CSV
syntax are invalid configuration.

Keep legacy manifests and fingerprints unchanged when the layout is unchanged.
Non-legacy layouts use manifest v2 with all three explicit policies and include
them in the plan fingerprint. Decode both versions strictly; never infer an old
slice's layout from current configuration. This extends the output wire of
ADR 0012 without changing canonical or import authority.

## Consequences

No database migration or input-dialect change is needed. Operators must verify
consumer support before enabling v2. Failed formatting preserves the installed
projection and never publishes an incomplete slice, but cannot undo an already
completed canonical transaction. LF describes record separators, not cell-content
normalization. CSV quoting does not provide spreadsheet formula sanitization.

See [CSV output format](../dev/csv-output-format.md) for configuration, ambiguity
rules, tests and recovery behavior.
