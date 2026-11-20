# Application test resources

## Purpose and structure

Synthetic profiles and independently reviewed consumer/CSV expectations for the
[test tree](../java/com/iocextractor/README.md). These are test inputs,
not production deployment templates. Outputs belong to temporary directories or
explicit redirected `target` roots, never back into fixtures or operator data.

| Resource | Owning scenario |
|---|---|
| [application-golden.yml](application-golden.yml) | GoldenPipelineIT: complete artifact-list policy with redirected canonical/projection output |
| [application-customer-routes.yml](application-customer-routes.yml) | CustomerRoutingPipelineIT: selected host-view routing layered on golden artifacts |
| [application-daemon-e2e.yml](application-daemon-e2e.yml) | DaemonIngestE2EIT: redirected daemon canonical/projection paths |
| [application-native-delivery.yml](application-native-delivery.yml) | NativeDeliveryLifecycleIT: composed fetch/processing/export with a fake SMB wire boundary and controlled UTC source |
| [application-selected-import-production.yml](application-selected-import-production.yml) | RouterSelectedImportDeliveryIT: real selected-plan import composition using isolated local storage |
| [golden](golden/README.md) | Synthetic messy source and independently expected mutable/immutable CSVs |
| [customer-routes](customer-routes/README.md) | Exact selected-customer-route CSV results |
| [consumer](consumer/README.md) | Standalone wire fixtures and isolated configuration/logging recipe drivers |

## Maintenance rules

Profiles inherit the main classpath baseline unless explicitly overridden. Boot
list properties replace complete lists: keep artifact/plan/contract declarations
complete and intentional. Test annotations/dynamic properties also redirect paths;
a YAML profile alone is not proof of isolation.

Preserve expected encoding, record separators, quoting, NULL distinctions, ordering
and marker hashes. Fixture changes must express an independently reviewed wire
or policy change. Do not accept producer-generated replacement expectations
without comparing their meaning. The reference consumer checks exact slice
membership, so its versioned slice directories contain only wire members.

The two `.java` programs under `consumer` are source fixtures compiled by recipe
tests, not normal `src/test/java` classes or a second application entry point.
See [test policy](../../../../../docs/TESTING.md) for evidence/timeout requirements.
