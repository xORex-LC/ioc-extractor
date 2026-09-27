# P1 parser and host-view implementation

Status: P1 implemented on 2026-09-27. The classes are available to the later
IOC plan integration; no document or processed-import route is activated by P1.

The shared `NetworkAddressParser` owns the supported lexical address form. It
accepts an ASCII DNS host or canonical dotted IPv4, optionally with HTTP(S), a
numeric port in `1..65535`, path, query and fragment. It does no DNS or network
I/O. `NetworkHostDeriver` creates one new typed IOC with the original source
context and leaves the original occurrence unchanged. `DefaultIndicatorFeatureExtractor`
uses the same parser for host/detail flags, avoiding divergent authority rules.

The application-level `ExactIndicatorParser` composes the independent domain
extractor and address parser without introducing a domain package cycle. P2 can
move this composition into the admitted processing module. It requires exactly
one extractor result spanning the entire
trimmed structured cell. A prefix match cannot turn malformed imported text
into a valid IOC. Document extraction remains lexical and may still find a
valid IOC substring in prose; P3 decides how to handle expected view failure
for an occurrence. P4 must use exact-cell admission for processed import. Both
paths must preserve source position or source-cell identity outside this parser.

Unsupported forms produce an explicit expected failure: non-HTTP schemes,
userinfo, bracketed/IPv6 hosts, malformed or empty ports, noncanonical IPv4,
non-ASCII DNS, whitespace/control characters and ambiguous authorities. There
is no silent prefix-host fallback. The existing document patterns were extended
to capture the port/path/query/fragment suffix of bare IPv4 and DNS names; URL
patterns still have priority. A trailing document delimiter is still handled by
the existing extractor, not by this parser.

The tests cover the customer URL and IP examples, scheme/case/port variants,
invalid forms, exact-cell rejection and RE2/J versus JDK extraction parity.
P2 will bind these operations into a compiled IOC plan; P3/P4 will decide the
final diagnostic at their existing failure-policy boundaries. Existing stored
rows are not rekeyed or backfilled.
