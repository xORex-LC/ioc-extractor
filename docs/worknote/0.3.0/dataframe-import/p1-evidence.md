---
title: "DATA-IMPORT-01 P1 evidence"
version: "0.3.0"
status: "Implemented with focused compatibility verification"
document_type: "Implementation evidence"
source_of_truth: false
language: "en"
---

# DATA-IMPORT-01 P1 evidence

## 1. Evidence boundary

P1 prepares existing integration families for import without starting an
intake worker or mutating canonical import data. It was implemented on branch
`feature/dataframe-import` after P0 in three logical changes:

- `c9812da1` mechanically renamed `adapter-sink-csv`/artifactId to
  `adapter-csv`/`ioc-adapter-csv` without moving its established Java package;
- `71e4ea19` added strict callback-streaming CSV reads and cursor-streamed,
  atomic mutable projection;
- `4f8f66e5` extracted import-neutral classification, canonical-change event
  and remote-watch contracts;
- `69d38c04` corrected the compiled-dialect assertion before the focused P1
  gate.

The full `make verify` gate is intentionally scheduled after P2 so it covers
the complete requested P1-P2 span. The checks below are focused P1 evidence,
not a fresh release gate.

## 2. Delivered boundaries

- Commons CSV construction and charset decoding remain exclusively in
  `adapter-csv`; application receives an immutable library-neutral dialect.
- The reader reports malformed/unmappable input, exact header-shape failures,
  aliases, ignored columns and physical record-separator violations without
  materializing the file or exposing raw header/IOC values in errors.
- Mutable projection streams the active snapshot in canonical ID order from
  one JDBC read boundary into a temporary file and installs it only with
  `ATOMIC_MOVE`; failure preserves the previous projection.
- `IndicatorClassifier` is reusable outside the extraction stage while the
  stage retains batch diagnostics/tracing ownership.
- `CanonicalArtifactsChanged` belongs to `application.artifact`, identifies a
  generic operation and can be emitted by ingest or later import promotion.
- SMB CHANGE_NOTIFY receives `RemoteWatchTarget`, not fetch matching policy;
  polling/detection ledgers remain correctness authority.
- The P0 `DataframeImportConfiguration` remains independently compiled and
  disabled by default; P1 does not activate runtime intake.

## 3. Executable evidence

| Check | Result |
|---|---|
| compiled catalog/dialect contract | `7` tests passed, including the parser-neutral delimiter, quote, header and null-literal values |
| CSV reader/projection and JDBC repository focused tests | `21` tests passed across the parser, projection, canonical repository and recovery integration paths |
| classification/ingest/sync seam focused reactor | `51` tests passed in selected classes; all `21` affected/upstream reactor modules succeeded |
| golden pipeline and on-demand export integration | `2` integration tests passed; committed golden resources retained their pre-P1 SHA-256 manifest `39de4ecde3b5501cc0313f734deb43662ae90c691e60bbaae66922a53bdab1c2` |
| parser laziness | callback failure stops after the second delivered record and propagates without reading the remainder |
| projection atomicity | a mid-stream failure leaves the installed target byte-identical and removes the incomplete temporary file |
| watch concurrency | existing re-arm, retry, lease, close and timeout tests pass with the narrow target contract |
| dependency direction | no new library or sibling-adapter dependency; application remains free of Commons CSV, JDBC, Spring and SMBJ |

`P1-SQL-TRUST`: the cursor-stream query accepts identifiers only from the
immutable validated dataframe schema and revalidates them at quoting; lifecycle
values remain prepared-statement parameters. The reviewed SpotBugs identity for
this boundary was recorded when the P1-P2 aggregate analyzer gate was prepared.

`P1-ATOMIC-PROJECTION`: the private projection write boundary catches a runtime
failure only to remove the incomplete temporary file, then rethrows the same
failure so application-level failure semantics are preserved. The exact
SpotBugs exception-policy identity is accepted as policy noise; atomicity tests
prove that the previous target survives and no temporary file remains.

`P1-IMMUTABLE-EVENT`: `CanonicalArtifactsChanged` validates each artifact name
and replaces the caller-owned collection with an unmodifiable copy in its
compact constructor. Its generated record accessor therefore cannot expose
mutable caller state; the exact `EI_EXPOSE_REP` identity is an analyzer false
positive, not an ownership escape.

## 4. Compatibility result

Ordinary document ingest still classifies exactly once per retained IOC and
emits the export nudge only after durable completion. Golden mutable and
immutable CSV bytes remain unchanged. Remote push is still a doorbell into the
same detection coordinator, and full scans/ledgers remain the recovery path.

P2 may now build canonical match/alias and mutation behavior without depending
on pipeline stages, Commons CSV types, complete fetch-source configuration or
materialized projection snapshots.
