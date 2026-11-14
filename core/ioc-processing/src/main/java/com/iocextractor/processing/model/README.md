# com.iocextractor.processing.model

## Responsibility and type

[ClassifiedIndicator](ClassifiedIndicator.java) pairs a domain `Indicator` with its
materialized `ClassificationDecision`. Mapping, filters and outer view operations
read this value without invoking the network policy again.

Both components must be non-null. The record preserves the supplied objects and
uses component equality. Construction does not recompute classification or verify
that the decision describes that indicator; the caller owns that consistency.
This value contains no occurrence position, canonical identity or durable state.

## Collaborators and verification

Only domain values are needed here. The [classification facade](../classification/README.md)
creates network/file decisions; [session](../session/README.md) can reuse them;
[mapping](../mapping/README.md) consumes them per selected view.

[Mapping tests](../../../../../../test/java/com/iocextractor/processing/mapping/ConfigurableRowMapperTest.java)
exercise reuse across columns and independently supplied column views. Precise
record guarantees remain in Javadoc; the [module README](../../../../../../../README.md)
owns dependency and execution rules.
