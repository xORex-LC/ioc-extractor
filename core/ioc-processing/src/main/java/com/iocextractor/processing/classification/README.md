# com.iocextractor.processing.classification

## Responsibility and type

[IndicatorClassifier](IndicatorClassifier.java) adapts a domain `MatchPolicy` to
all currently supported IOC categories without depending on pipeline stages.
It uses domain values only; it neither stores decisions nor emits diagnostics.

## Classification and policy identity

Network indicators delegate to the supplied policy once per `classify` call.
File indicators bypass that policy and receive a neutral decision: original value
as feature value/host, no structural flags, `HostKind.UNKNOWN`, rule index `-1`,
no matched predicate names and null mask codes. Neutral classification does not
validate a hash or apply presentation casing.

`supports` recognizes the current `NETWORK` and `FILE` categories. A future category
requires an explicit supported branch; `classify` rejects unsupported categories.
The facade does not normalize values or implement the domain's first-match rules.

`sharesPolicyWith` compares the underlying policy by object identity, including
between separate wrappers. It is not a semantic equivalence or configuration-hash
check. [Session](../session/README.md) uses this identity to decide whether reuse is
safe; callers must keep policy behavior fixed throughout that scope. `name()` is
a value-free class name for reporting, not a unique policy identity.

## Verification

[IndicatorClassifierTest](../../../../../../test/java/com/iocextractor/processing/classification/IndicatorClassifierTest.java)
checks network delegation and file-policy bypass. Domain policy behavior belongs
to the [domain classification reference](../../../../../../../../ioc-domain/src/main/java/com/iocextractor/domain/classify/README.md);
source/type-sensitive reuse is covered by [session tests](../../../../../../test/java/com/iocextractor/processing/session/IndicatorProcessingSessionTest.java).
