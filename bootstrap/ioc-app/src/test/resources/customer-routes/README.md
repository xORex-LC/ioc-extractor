# Selected customer-route expectations

These five CSVs are exact expected outputs for
[CustomerRoutingPipelineIT](../../java/com/iocextractor/CustomerRoutingPipelineIT.java):
[masks](masks.csv), [IP list](ip-list.csv),
[address blacklist](address-blacklist.csv), [hashes](hashes.csv) and
[aggregate](IOC_aggregate.csv).

The scenario explicitly selects `customer-hosts` from
[application-customer-routes.yml](../application-customer-routes.yml)
and layers it on the golden artifact policy. It proves that the selected plan's
views/destinations affect actual canonical writes and projections. It does not
make host cleanup a default for all documents or alter every artifact's own
normalization/identity policy.

Keep expectations independent of producer helpers and preserve exact formatting.
The [golden source](../golden/README.md) is synthetic; outputs
are redirected by the test. Run from the repository root:

```bash
make test-one MODULE=bootstrap/ioc-app TEST=CustomerRoutingPipelineIT
```
