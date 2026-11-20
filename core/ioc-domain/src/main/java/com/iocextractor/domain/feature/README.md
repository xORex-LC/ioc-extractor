# com.iocextractor.domain.feature

## Responsibility and boundary

Normalize boundary punctuation, parse supported network addresses, derive host or
scheme-free views and expose structural features. Only `model` is an allowed
domain dependency. Parsing performs no DNS lookup or URI dereferencing; PSL host
kind comes through `HostClassifier`, implemented by the external PSL adapter.

## Types

| Source | Responsibility |
|---|---|
| [IndicatorNormalizer](IndicatorNormalizer.java), [DefaultIndicatorNormalizer](DefaultIndicatorNormalizer.java) | Normalization seam and conservative boundary trimming |
| [IndicatorFeatureExtractor](IndicatorFeatureExtractor.java), [DefaultIndicatorFeatureExtractor](DefaultIndicatorFeatureExtractor.java) | Normalize/parse a value and classify a successfully parsed host |
| [IndicatorFeatures](IndicatorFeatures.java) | Normalized value, host, port/path/query/fragment flags and host kind |
| [HostClassifier](HostClassifier.java), [HostKind](HostKind.java) | Host-classification port and `IP/REGISTRABLE/SUBDOMAIN/ONION/UNKNOWN` vocabulary |
| [NetworkAddressParser](NetworkAddressParser.java) | Supported-form validation, address shape and typed input failures |
| [NetworkHostDeriver](NetworkHostDeriver.java) | Lowercase host with derived IPv4/domain type and original source |
| [NetworkSchemeDeriver](NetworkSchemeDeriver.java) | Remove only an optional HTTP(S) scheme and preserve the remaining spelling |
| [NetworkAddressClassifier](NetworkAddressClassifier.java) | Shared bare-IPv4 predicate used by outer artifact routing |

## Normalization and supported forms

The default normalizer removes only surrounding semicolon, comma, double/single
quote, ASCII space, tab, CR and LF. It preserves null/empty input, internal spelling,
case and a trailing dot. Normalization neither validates an IOC nor supplies
artifact-specific lowercase/uppercase policy.

`NetworkAddressParser` expects an exact, already refanged/normalized value. It
accepts case-insensitive HTTP(S) schemes or no scheme, ASCII domain names with an
alphabetic TLD of at least two characters, and dotted IPv4 without padded octets.
A numeric authority port must be in `1..65535`. Authority ends before `/`, `?` or
`#`, so a colon in a path is not interpreted as a port. The parsed host is lowercased.
A query flag requires `?` before a fragment; `?` inside a fragment is not a query.

Unsupported/malformed forms return `EMPTY`, `UNSUPPORTED_SCHEME`,
`INVALID_AUTHORITY`, `INVALID_HOST`, `INVALID_PORT` or `UNSUPPORTED_ADDRESS_FORM`.
Notable rejected forms include userinfo, IPv6, single-label hosts, raw Unicode
host labels, trailing host dots, underscores, whitespace/control characters and
repeated fragment delimiters. This is the project's supported address shape,
not a general URI parser or a DNS existence check. The result contains exactly
one address or failure reason.

## Feature and derived-view contracts

On parse success the default feature extractor delegates host kind and records
structural flags, retaining the normalized full value. On failure it returns that
value as `host`, all flags false and `HostKind.UNKNOWN`; it does not throw an
expected parse failure or call the host classifier. Feature extraction does not
rewrite the supplied `Indicator` or validate consistency with its declared type.

Both derivers accept network-category indicators only, validate the complete
address and preserve `SourceContext`. Host derivation returns a lowercase `IPV4`
or `DOMAIN` value. Scheme removal preserves host case, port, path, query and
fragment spelling; the resulting type is `URL` if any suffix/port flag is present,
otherwise `IPV4` or `DOMAIN`. They return typed failures rather than an empty IOC.

Bare IP requires declared type `IPV4`, host kind `IP` and no port, path, query or
fragment. A `URL`-typed `http://192.0.2.44` therefore is not bare even without a
suffix. Callers must pass features for the same indicator. See [feature tests](../../../../../../test/java/com/iocextractor/domain/feature/README.md)
and [processing](../../../../../../../../../docs/dev/processing.md) for local evidence and cross-module use.
