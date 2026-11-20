# adapters/adapter-manifest-json-jackson

## Purpose and boundary

Jackson JSON implementation of the application `SliceManifestCodec` port for
immutable export manifests. The internal reactor artifact is
`com.iocextractor:ioc-adapter-manifest-json-jackson`; it is not a runnable
application or a separately admitted reusable library.

This module owns model/document mapping, JSON field names/order, deterministic
UTF-8 encoding and the manifest-version gate. Application records retain their
invariants without Jackson annotations. File access, CSV generation, hashes,
visibility markers, ledgers and remote delivery belong to other owners.

## Structure

| Directory / file | Responsibility |
|---|---|
| [pom.xml](pom.xml) | Application/error contracts and the Jackson integration family |
| [Production package](src/main/java/com/iocextractor/adapter/out/manifest/json/README.md) | Codec, private document records, byte/version/failure boundaries and package Javadoc |
| [Test package](src/test/java/com/iocextractor/adapter/out/manifest/json/README.md) | Exact v1 bytes, v1/v2 mapping and malformed/unknown/duplicate JSON checks |

There is one production package and one matching test package. Synthetic models
and expected JSON are inline; there is no module-local test-resource directory.

## Dependencies

Direct production dependencies are `ioc-application`, `ioc-platform-errors`
and Jackson databind/core/annotations. This adapter uses the
`com.fasterxml.jackson` Jackson 2 family, managed by the parent POM. Java 21 is
the project baseline. The application TCK supplies test-scoped annotations.

There is no production Spring/Boot dependency, auto-configuration or injected
ObjectMapper. The codec constructs its own reader/writer configuration; a
service's Spring JSON settings do not configure this instance. Do not import
sibling adapters or move Jackson annotations into application/domain records.
Bootstrap wires the implementation behind the inward port in
[AppConfig](../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/AppConfig.java).

## Entry points and contracts

Construct `JacksonSliceManifestCodec` explicitly and use `encode(SliceManifest)`
or `decode(byte[])`. Both manifest versions 1 and 2 are accepted;
`SUPPORTED_VERSION` names the newest supported version, not the only one.
The encoder honors the caller's manifest version rather than selecting it.

V1 preserves the original CRLF/ALL/ALL CSV layout and its historical JSON bytes.
V2 records explicit CSV record-separator/header-quoting/value-quoting policies.
JSON itself always uses UTF-8 and LF pretty-printing regardless of the CSV
charset or record separator. Field order is explicit; artifact-array order comes
from the model. Stable bytes require the same ordered model and qualified codec
dependency baseline, not merely semantically equivalent JSON.

Unknown/duplicate properties and unsupported versions are rejected. Required
references and semantic invariants are checked during mapping/model construction;
this is not complete JSON field-presence validation. The production README
explains primitive defaults, null arguments and exception boundaries.

The codec does not hash or authenticate input. Callers hash the exact returned
or stored bytes, without reformatting/re-encoding an existing manifest. The CSV
adapter owns safe file names, exact slice membership, file digests and `_SUCCESS`
verification through `SliceManifestCodec`, without depending on this concrete
Jackson implementation.

## Verification and related docs

Run from the repository root:

```bash
make test-integration-module MODULE=adapters/adapter-manifest-json-jackson
```

The module's single suite is Failsafe-discovered; Maven `test` alone does not run
it. Full reactor acceptance uses `make verify`. The test README separates
codec fixtures from independent consumers and filesystem integrity checks.

See [immutable export](../../docs/dev/artifact-export.md),
[CSV layout](../../docs/dev/csv-output-format.md) and
[ADR 0043](../../docs/ADR/0043-configurable-csv-output-format.md).
The [CSV writer package](../adapter-csv/src/main/java/com/iocextractor/adapter/out/sink/csv/README.md)
owns physical slice publication, recovery and retention. A codec round trip
alone does not establish slice availability, durable completion or delivery.
