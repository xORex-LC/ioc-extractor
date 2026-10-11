# CAP-7C bounded native-cost experiment

This execution record implements the finite cycle agreed after the
[feasibility review](capacity-feasibility-review.md). Native SQLite remains the
production default; no staged activation, resource-policy relaxation or
performance acceptance is part of this experiment.

## Measurement contract

The existing physical full-service C5 harness owns configuration, Spring
composition, DOCX/CSV fixtures, canonical/output/provenance oracles, exact
canonical admission and whole-JVM allocation accounting, control/cleanup,
cgroup resources and private-state removal. `--native-cost-cycle` runs a fixed
number of native forks. A completed old-budget `FAIL` remains a `FAIL`; collector,
oracle and cleanup failures stop the series. Series `COMPLETE` describes evidence
completeness only and never accepts C5/G6. No retry-until-pass is used.

Additional fingerprints cover canonical key/ID/lifecycle assignments, aliases,
field ranks, receipt payloads and ordinals. They intentionally exclude varying
UTC timestamps and private occurrence IDs. The existing independent oracle
continues to check final fields/keys, sources, projections and reusable slots.

Diagnostic JFR runs are separate from primary timings. The streaming
`native-processing-profile` command attributes sampled allocation weights to
the nearest IOC stack frame, including the method descriptor. These weights
are estimates, not exact allocated bytes. Execution/native stack samples are
not elapsed method times or CPU percentages; recordings include startup and
shutdown. ECS stage durations provide the measured elapsed breakdown, without
inventing exact subphase durations from stack samples.

## Initial diagnostic baseline and selected mechanism

At source `22467cddf6aeb173c7d2c522b400ca2a8ba2c2d1`, the freshly packaged
application SHA-256 was
`1cfe167d9ba00f77c668361f9defce803c281ee2ad6319f3263e5ca1b4cec393`.
The 100,000-occurrence unique DOCX diagnostic completed its independent output
checks. Its conservative local window was 112.691 s; unchanged local-time and
writer-hold screens failed. This profiled duration is not a primary comparison.
Raw evidence is retained privately at `.dev/cap-bounded-native-profile`.

`DocumentRowCodec.text(DataInputStream)` accounted for 5,145,007,024 bytes of
sampled weight, 29.00% of the whole-recording allocation weight, the largest
attributable allocation site. Encoding is a separate overload and is excluded
from that figure. The decoder copies every field's bytes, constructs a strict
UTF-8 decoder and allocates byte/character buffers on every field of every
prepared-row pass.

The single selected mechanism is field decoding. The proposed implementation
reads the unchanged version-1 payload through validated slices of the bounded input,
constructs ASCII strings directly from their validated slice and uses a strict
UTF-8 decoder for non-ASCII slices. All state belongs to one decode invocation;
there is no global cache or shared mutable decoder. It must preserve missing
fields, explicit nulls, order/rank, ID metadata, exact bounds, corrupt-input
rejection, and sealed replay. Identity validation, winner selection, canonical
mutation and receipt durability remain mandatory.

## Execution status

The native-only measurement mode and deterministic fingerprints have tools
regressions for incomplete collection, retained screen failures and changes to
identity/rank/receipt payload. At harness source `076ca6b3`, three primary native
DOCX forks completed before the production change: 70.138, 70.988 and 118.532 s.
The long third window remains in the series. All three retained the unchanged
time/writer/control screen failures and identical deterministic fingerprints.
Three selected host/COALESCE import forks completed in 18.280, 18.568 and
17.620 s and passed their individual unchanged screens. This does not close the
complete capacity matrix.

Slice decoding is implemented without changing encoding or version 1. The
workspace integration suite covers sealed replay and resource ownership. Added
regressions use an independently encoded mixed ASCII/Unicode fixture (including
supplementary characters and NUL), exact field-byte boundaries, byte-for-byte
encoding, missing/null/empty cells, order/rank, independent string ownership,
overlong/surrogate/out-of-range/truncated UTF-8 and every truncated prefix of a
valid row. Candidate performance and final quality results remain pending; no
improvement is claimed from the prototype alone.
