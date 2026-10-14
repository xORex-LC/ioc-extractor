---
title: "DATA-AGGREGATE-01 — network carrier amendment"
version: "0.3.0"
status: "Accepted URL and host-path routing"
document_type: "Design amendment"
source_of_truth: false
language: "en"
---

# Network carrier amendment — 2026-09-22

This owner clarification supersedes the earlier IP/hash-only scope and constant
NULL match columns. It amends [technical design](technical-design.md).

## Accepted contract

- url_match carries URL/address text, following the intended masks.mask value
  semantics; it never carries mask classification codes such as u:hEX.
- host_match carries only clean domain names/FQDNs, without scheme, port or path.
- ip_address and hash retain their meanings. Each current row describes one
  independently observed IP, URL, domain or hash. No correlation/merging is added.
- Configuration owns artifact routing and shape constraints. A universal ban on
  multiple populated carrier columns must not be embedded in shared storage.
- Full normalized four-carrier tuple remains identity. Name ordering, empty-name
  preservation, empty start, lifecycle and managed-import requirements remain.

## Current-code findings

The classpath masks artifact accepts IPV4, DOMAIN and URL, excluding is-bare-ip,
then maps value with lower-host. Thus masks.mask includes bare domains as well as
URL-shaped addresses; copying its entire acceptance rule into aggregate url_match
would overlap clean-domain routing.

The configured DOMAIN regex accepts a path suffix. NetworkAddressClassifier and
IndicatorFeatures already distinguish bare IP, host, port, path and query. The
address.url provider returns every non-bare-IP value, including hashes when an
artifact admits them; it is not by itself a safe URL-only provider.

RegexIndicatorExtractor claims overlapping spans in configured type priority.
An HTTP URL does not also yield its embedded domain/IP as another observation.
Keep that behavior. Extracting a host from a URL and filling host_match would
create the combined representation the owner explicitly deferred.

## Accepted routing

| Observed value | Populated carrier | Other carriers |
|---|---|---|
| 192.0.2.1 | ip_address | NULL |
| Example.org / sub.example.org | host_match, normalized host case | NULL |
| https://Example.org/CaseSensitive.exe | url_match, existing lower-host semantics | NULL |
| example.org/path | url_match | NULL |
| 192.0.2.1:8080/file | url_match | NULL |
| MD5 / SHA1 / SHA256 | hash, uppercase | NULL |

The owner confirmed full URLs and host-plus-path values without a scheme in
url_match; bare domains and bare IPs are excluded from that column. Preserve
the supplied scheme or its absence; never invent a protocol. This field contract
is broader than a strict absolute-URL type.

The owner also accepted scheme-less host:port without a path in url_match,
for example example.org:8443 and 192.0.2.1:8080. Express this as a configurable
structural rule; preserve the observed address without adding a scheme. Test
trailing-dot FQDN, IDN/punycode, fragment, userinfo and scheme-less host:port
boundaries against actual extraction/normalization. Existing
regexes are not a promise of complete FQDN/URL grammar coverage. Do not truncate
an unsupported decorated address and silently call it a clean domain. No DNS
resolution, inferred ownership or automatic protocol insertion is proposed.

## Narrow implementation extension

Use existing value providers and transforms plus a compiled column applicability
condition over type and existing classification features. Keep when-type and
when-types; add a reusable structural gate through the predicate registry for
clean-domain versus URL-shaped address. Exact DSL syntax belongs to P1. Do not
special-case the artifact/column names in Java or change address.url globally.

The clean-domain condition must establish a DOMAIN observation and absence of
scheme/port/path/query/other URL decorations, not merely return features.host.
The URL-address condition selects the agreed complementary network shape, excluding
hashes and bare IP. Applicability is evaluated before provider execution; gates
and transformations are shared with processed import and fingerprinted.

Configure a carrier group [ip_address, url_match, host_match, hash] with permitted
nonempty count exactly one in this contract. This is a proposed generic row-shape
constraint, compiled once, applied to document preparation and managed-import
validation (including as-is). It is not a hardcoded invariant of the canonical
row model. Reuse an existing validation facility if it can express this contract;
otherwise add a small validator, not a general expression engine. Reject compound
import rows under the current contract instead of splitting them or guessing links.
Each carrier also requires its own value validation; cardinality alone is insufficient.

A later policy can allow multiple carriers, but changing cardinality is not a
correlation algorithm. A future structured source must provide relationship
identity/evidence, precedence, lifecycle and conflict rules. Current tuple identity
means (IP,NULL,NULL,NULL) differs from (IP,URL,FQDN,NULL); adding fields would be
an identity change, not today's name-only update. Future enrichment needs an
explicit versioned identity/matching transition and must not merge by any one
matching carrier or perform implicit DNS correlation.

No new module, framework, published concurrency API or durable ordering algorithm
is required by this amendment. Implementation grows in mapping/validation/import
contracts and fixtures. URL/FQDN handling is now part of initial qualification.

## Additional qualification

- One carrier per generated/imported row for every supported type and shape.
- DOMAIN-with-path cannot enter host_match; hash cannot enter url_match.
- URL extraction does not synthesize a host/IP row or combine carrier values.
- lower-host preserves path/query case under the existing transform contract.
- As-is imports cannot bypass clean-domain, URL, IP or hash validation.
- Future permissive cardinality policy does not require changing generic storage;
  no current test asserts that every artifact must have exactly one carrier.
- Full-tuple identity and name precedence remain unchanged for all new carriers.
