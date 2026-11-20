# Independent consumer fixtures

## Structure

| Location | Responsibility |
|---|---|
| [export-slice-v1](export-slice-v1/) | Exact v1 CRLF complete slice: manifest, marker and one masks CSV |
| [export-slice-v2](export-slice-v2/) | Independent v2 LF slice distinguishing unquoted NULL from quoted literal NULL text |
| [configuration](configuration/README.md) | Closed-input configuration guide consumer source |
| [logging](logging/README.md) | Boot logging guide driver and application-start JSON/query expectations |

Each slice has exactly `manifest.json`, `_SUCCESS` and
`masks_list_generated.csv`. Keep READMEs and other explanatory files outside
these versioned directories. `_SUCCESS` binds the exact manifest bytes; the
manifest binds the artifact bytes. Reformatting JSON or line endings changes
those identities even when parsed values appear equivalent.

[ReferenceArtifactConsumerTest](../../java/com/iocextractor/consumer/ReferenceArtifactConsumerTest.java)
uses these wires independently of producer types and tests corrupt temporary
copies. The recipe consumers compile documentation examples without IOC classes;
they qualify documented APIs and output semantics, not external repository or
server availability. [The parent map](../README.md)
links composed test scenarios and maintenance rules.
