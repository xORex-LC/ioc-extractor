# Jackson slice-manifest tests

Behavioral checks for the [production codec](../../../../../../../../main/java/com/iocextractor/adapter/out/manifest/json/README.md).
The single suite uses `@IntegrationTest` and Failsafe `*IT` naming. It exercises
the actual Jackson dependency in memory, with inline synthetic records/JSON;
there is no Spring context, filesystem fixture tree or external service.

| Suite | Responsibility |
|---|---|
| [JacksonSliceManifestCodecIT.java](JacksonSliceManifestCodecIT.java) | Wire bytes, version/layout mapping and malformed/ambiguous input rejection |

## Covered contracts

- Exact independently written v1 expected JSON: ordered fields, pretty spacing,
  LF indentation, UTF-8 non-ASCII text, escaped quotes, explicit null coverage
  time and no trailing newline. Repeated encoding matches the expected bytes.
- V1 round trip preserves the model/coverage; equivalent formats built from
  differently ordered maps produce the same bytes.
- V2 records explicit layout policies and round-trips them; legacy v1 decoding
  restores CRLF. Version 2 without policies and v1 with non-null new policies fail.
- Unsupported version, malformed JSON, unknown root property and duplicate
  property are rejected as `IocExtractorException`.

The fixed v1 expected text is a byte oracle, while round trips only check
producer/reader agreement. V2 currently has field/value and round-trip checks,
not an independent full-document golden byte fixture in this module. The tests
do not establish complete missing/null-field rejection, every Jackson coercion,
resource isolation, arbitrary-input limits or concurrent-use stress behavior.

## Verification and wider evidence

Run from the repository root:

```bash
make test-integration-module MODULE=adapters/adapter-manifest-json-jackson
```

This module has no Surefire suite; running Maven `test` alone skips its local
contract. Full reactor acceptance uses `make verify`.
[TESTING.md](../../../../../../../../../../../docs/TESTING.md) owns naming, tags and evidence rules.

Filesystem forcing, marker/digest binding, exact directory membership, recovery
and retention are checked in the
[CSV writer tests](../../../../../../../../../../adapter-csv/src/test/java/com/iocextractor/adapter/out/sink/csv/README.md).
Bootstrap's [OnDemandExportIntegrationIT](../../../../../../../../../../../bootstrap/ioc-app/src/test/java/com/iocextractor/OnDemandExportIntegrationIT.java)
checks the assembled export path. Its separate
[ReferenceArtifactConsumerTest](../../../../../../../../../../../bootstrap/ioc-app/src/test/java/com/iocextractor/consumer/ReferenceArtifactConsumerTest.java)
uses independent v1/v2 slice fixtures without producer types; those fixtures
are consumer protocol evidence, not this codec's full byte oracle. Local codec
success alone does not qualify a deployed filesystem, remote transport or
end-to-end durable publication.
