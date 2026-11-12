# Golden extraction and export fixtures

[source.html](source.html) is a synthetic messy IOC document used by the composed
pipeline tests. It exercises section attribution, defanging, IOC families,
network classification variants and duplicate handling without operator data.

| Expected wires | Contract |
|---|---|
| [masks](expected-masks.csv), [IP list](expected-ip-list.csv), [address blacklist](expected-address-blacklist.csv), [hashes](expected-hashes.csv), [aggregate](expected-ioc-aggregate.csv) | Complete mutable projection outputs under the golden profile |
| [export masks](expected-export-masks.csv), [export aggregate](expected-export-ioc-aggregate.csv) | Immutable export outputs with their external identity contract |

[GoldenPipelineIT](../../java/com/iocextractor/GoldenPipelineIT.java)
uses the real graph and an independent reference consumer for repeated
extraction/deduplication and mutable output.
[OnDemandExportIntegrationIT](../../java/com/iocextractor/OnDemandExportIntegrationIT.java)
checks the immutable export expectations. Selected-route scenarios reuse the
source while owning their own expectations.

Keep mutable canonical IDs and immutable reusable export-slot IDs distinct.
Expected bytes pin CSV formatting, headers, casing, NULL handling and row order;
change them only for an explained contract/policy change. Large-source/load and
live-server qualification remain separate. Run from the repository root:

```bash
make test-one MODULE=bootstrap/ioc-app TEST=GoldenPipelineIT
```

[Parent resource map](../README.md) describes profile/isolation rules.
