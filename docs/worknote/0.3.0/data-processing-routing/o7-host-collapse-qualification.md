# O7 mass URL-to-host/IP qualification

Status: protocol fixed before primary sampling; implementation and smoke checks
complete. This extends O7 qualification only; customer resource acceptance remains
deferred. No production processing policy or implementation is changed.

## Workload and independent semantic oracle

Use the production Spring comparison probe, real YAML binding/preflight, selected
preparer, extractor/classifier/Camel, physical HTML/CSV and isolated SQLite stores.
All URLs carry a scheme, port, unique path, query and fragment. Half of the final
addresses are domain hosts and half IPv4. HTTP and HTTPS variants share final
hosts. Consecutive repeated URLs and different URLs of each host are retained.

The document repeats the same URL set under `БИБ-0001` and `БИБ-0002`. All network
artifact branches consume `host`; masks explicitly admits bare IPv4 so both input
families have a primary output. Masks/IP/blacklist KEEP_FIRST must retain the first
source; aggregate LAST_NONEMPTY must retain the second source and complete row.
Independent Python assertions check final carrier fields, configured match codes,
SHA-256 keys over canonical JSON and the selected source's provenance. Each winner
contributes one canonical occurrence; this is not the number of extracted URLs.

Processed import uses the same host view and masks admission. Each host has a
stable `Feed Alpha` or `Feed Beta` label so the configured fill-missing COALESCE
policy remains compatible. Every CSV row is staged; representatives/COALESCED,
receipt counts, warnings, terminal state, IDs and canonical provenance are checked.
Different labels on the same host are exercised by the document, not artificially
merged across incompatible import source cells.

Each document URL creates one expected DEBUG overlap diagnostic. Total severity,
retained count/code and the full outcome summary are preserved. No warning is
discarded or early deduplication added. Warm-up final hosts/IPs are disjoint.

## Measurement protocol

| Profile | Document rows / distinct URLs / final hosts | Import rows / distinct URLs / final hosts | Warmups |
|---|---:|---:|---:|
| First | 8,000 / 2,000 / 20 | 2,000 / 500 / 20 | 0 |
| Warm | 8,000 / 2,000 / 20 | 2,000 / 500 / 20 | 1 |
| Large warm | 100,000 / 25,000 / 20 | 10,000 / 2,500 / 20 | 1 |

Collect five fresh JVM samples per workload/revision/profile. Compare original
production `08d30621` and the retained O1–O5 implementation with byte-identical
test/tools harness, input/configuration digests, dependency versions, logging and
JVM flags. The baseline overlay changes test/tools files only. Select the host
route in both revisions: compatible processing without cleanup produces different
fields and is not a valid comparator. Selected-only reports leave compatible
ratios and historical envelope status null, rather than fabricating acceptance.

Primary forks pin `DEBUG=false TRACE=false LOGGING_LEVEL_ROOT=WARN` and
`-Xms128m -Xmx512m`; startup is separate and no concurrent build/benchmark runs
during sampling. Time, throughput, caller allocation, GC, sampled heap/current RSS
and historical VmHWM are retained. Cross-revision comparisons require full equal
signatures and outcomes in every sample. Collect separate agent/JFR samples on
the large warmed profile in both revisions; their timing is diagnostic only.

The selected-only driver runs one revision at a time. Record the actual series
order; independent samples are not an alternating before/after crossover and no
portable latency/whole-process allocation claim follows. Preserve all failures
and raw logs. Resource-budget compliance is evaluated separately.

## Reproduction

```bash
DEBUG=false TRACE=false LOGGING_LEVEL_ROOT=WARN make processing-route-comparison \
  COMPARISON_ARGS='--shape host-collapse --selected-only --pairs 5 --document-rows 8000 --document-unique 2000 --import-rows 2000 --import-unique 500 --workspace .dev/new-cleanup-first'
# Warm: add --warmups 1 and use a new workspace.
# Large: --warmups 1 --document-rows 100000 --document-unique 25000
#        --import-rows 10000 --import-unique 2500
# Diagnostic: add --diagnostics --pairs 1 to the large profile.
```

Use the same driver/probe/agent/resources overlay in an isolated `08d30621`
worktree. All generated logs/JFR/DBs stay under `.dev`; only reviewed reports
are retained in the execution bundle. Original O7 measurements remain unchanged.
