# Reference artifact consumer tests

| File | Responsibility |
|---|---|
| [ReferenceArtifactConsumer.java](ReferenceArtifactConsumer.java) | Repository-owned test reader for exact CSV and immutable slice wires |
| [ReferenceArtifactConsumerTest.java](ReferenceArtifactConsumerTest.java) | Independent v1/v2 fixtures, NULL semantics and corruption rejection |

The reader deliberately avoids producer DTOs/helpers. It checks declared fields,
CSV framing/header/rows, artifact byte hashes, manifest-marker binding and exact
slice membership. It is a test fixture, not a production SDK or a compatibility
promise for an arbitrary external consumer. Golden/export integration scenarios
also use it to validate the producer from the consumer side.

[Fixtures](../../../../resources/consumer/README.md) preserve exact bytes and
member names. Do not add a README inside a versioned slice: that would itself
violate the consumer's member-set check. Discuss expected wire changes in
[CSV format](../../../../../../../../docs/dev/csv-output-format.md) and [artifact export](../../../../../../../../docs/dev/artifact-export.md)
before updating expected data; do not regenerate it from the implementation
merely to make a failing contract green.

Run from the repository root:

```bash
make test-one MODULE=bootstrap/ioc-app TEST=ReferenceArtifactConsumerTest
```
