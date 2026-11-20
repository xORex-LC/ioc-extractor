# Feature and network-view tests

## Responsibility and suites

Pure tests for the supported network shape and normalization boundary. Host-kind
doubles keep parsing tests independent of external PSL data.

| Source | Contract covered |
|---|---|
| [DefaultIndicatorNormalizerTest](DefaultIndicatorNormalizerTest.java) | Exact boundary punctuation set, null/empty values, interior/trailing-dot preservation |
| [DefaultIndicatorFeatureExtractorTest](DefaultIndicatorFeatureExtractorTest.java) | Host/path/query/port features, path colon versus authority port and malformed-authority fallback |
| [NetworkAddressParserTest](NetworkAddressParserTest.java) | Host/scheme derivation, provenance, suffix flags, typed invalid/unsupported inputs, length/control boundaries and exclusive outcomes |
| [NetworkAddressClassifierTest](NetworkAddressClassifierTest.java) | Bare IPv4 versus URL/domain/port/path/query/fragment cases and declared-type/host-kind requirements |

`NetworkAddressParserTest` also owns both derivers' tests. Synthetic examples assert
that source context is retained and originals are unchanged. The fixed host-kind
stub in feature extraction tests does not establish real registrability or IP
classification; actual PSL integration belongs to the adapter suite.

See the [production feature reference](../../../../../../main/java/com/iocextractor/domain/feature/README.md)
for the supported forms and the [test root](../README.md)
for execution commands and wider evidence limits.
