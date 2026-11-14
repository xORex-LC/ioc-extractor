# com.iocextractor.processing.parse

## Responsibility and type

[ExactIndicatorParser](ExactIndicatorParser.java) composes a domain `IndicatorExtractor`
and `NetworkAddressParser` for a structured cell. Document extraction can search
within text; a structured cell must contain one complete accepted IOC.

## Input and outcomes

The caller refangs the cell first. This parser strips surrounding whitespace,
then requires exactly one accepted extractor result at position zero whose matched
value length equals the stripped cell length. It preserves that raw result's value
and type. Returned positions refer to the stripped cell, not the original document
or untrimmed CSV field. The parser neither attributes source nor classifies it.

Null/blank input returns `NO_INDICATOR`. Nonblank input with zero, partial or multiple
accepted matches returns `PARTIAL_OR_MULTIPLE_MATCH`. A complete network match must
also pass the domain supported-address parser or yields `INVALID_NETWORK_ADDRESS`.
File matches rely on the supplied extraction patterns; this class does not provide
an independent hash grammar. A result has exactly one raw indicator or failure.

These are expected input outcomes. Extractor/programming exceptions propagate;
they are not converted into a data rejection. No IO, registry loading, mapping or
acceptance checkpoint lives here.

## Collaborators and verification

[Processed-import composition](../../../../../../../../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/RouterProcessedImportRowPreparer.java)
refangs selected VALUE cells before parsing. Import contract state such as ABSENT
or explicit NULL is handled outside this parser.
[ExactIndicatorParserTest](../../../../../../test/java/com/iocextractor/processing/parse/ExactIndicatorParserTest.java)
uses a synthetic extractor to isolate completeness/failure rules; it does not
qualify production regex or source encoding. Supported network forms belong to
the [domain feature reference](../../../../../../../../ioc-domain/src/main/java/com/iocextractor/domain/feature/README.md).
