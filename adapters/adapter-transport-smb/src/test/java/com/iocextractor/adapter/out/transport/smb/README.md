# SMB adapter tests

## Purpose and structure

Executable contracts for the [production package](../../../../../../../../main/java/com/iocextractor/adapter/out/transport/smb/README.md).
Nine `*Test` suites run in Surefire. Failsafe discovers two deterministic
`@IntegrationTest` suites backed by in-memory share/session fakes and four
property-gated `@ExternalTest` / `@ContractTest` suites requiring a real server.
Fixtures are inline or temporary local files; there is no test-resource tree.

| Suite / fixture | Observable contracts |
|---|---|
| [ConnectTimeoutSocketFactoryTest](ConnectTimeoutSocketFactoryTest.java) | TCP connect timeout wiring |
| [SmbEndpointSettingsTest](SmbEndpointSettingsTest.java) | Password copies/redaction, port boundaries and complete credential-profile selection |
| [SmbExceptionMapperTest](SmbExceptionMapperTest.java) | Error taxonomy, preserved mapped failures and raw resource/quota NTSTATUS |
| [SmbjShareClientFactoryTest](SmbjShareClientFactoryTest.java) | Request/connect/socket separation, port, encryption modes, unencrypted-session rejection and failed-open cleanup |
| [SmbSessionPoolTelemetryTest](SmbSessionPoolTelemetryTest.java) | Open failure, capacity signal and successful reconnect |
| [SmbTransportTelemetryTest](SmbTransportTelemetryTest.java) | Owner aggregation, resource leases and concurrent idempotent close |
| [SmbjChangeNotifySessionFactoryTest](SmbjChangeNotifySessionFactoryTest.java) | Header status, empty/overflow responses and pending-future outcomes |
| [SmbChangeNotifyWatcherTest](SmbChangeNotifyWatcherTest.java) | Signal/re-arm, idle wait, lease reopen, failure/backoff, telemetry and cancellation |
| [SmbImportChangeSignalSourceTest](SmbImportChangeSignalSourceTest.java) | Source-scoped hint, partial-start cleanup and reverse aggregate close |
| [SmbFileTransportIT](SmbFileTransportIT.java) | Fake-backed publish order, marker idempotency, size verification, partial cleanup, reconnect and paths |
| [SmbManagedImportSourceLifecycleIT](SmbManagedImportSourceLifecycleIT.java) | Fake-backed polling/stability, claim-before-download, mutation/collision/orphan recovery, probe, disposition, retention and shared pool |
| [SmbManagedImportContractIT](SmbManagedImportContractIT.java) | Live rename/share conflict, destination collision and new-session orphan/quarantine flow |
| [SmbChangeNotifyContractIT](SmbChangeNotifyContractIT.java) | Live creation signal and notification after idle longer than request timeout |
| [SmbManagedImportHardeningContractIT](SmbManagedImportHardeningContractIT.java) | Live pre-provisioned namespace, two identities, capability/reconnect/disposition and exact retention |
| [SmbEncryptionContractIT](SmbEncryptionContractIT.java) | Successful live `REQUIRED` encrypted session before listing |
| [SmbContractTestSupport](SmbContractTestSupport.java) | Endpoint/environment fixture and live held-writer handle |
| [TestImportSnapshotStore](TestImportSnapshotStore.java) | Local snapshot fixture for source-lifecycle tests |

## Offline verification and evidence limits

Run from the repository root:

```bash
make test-module MODULE=adapters/adapter-transport-smb
make test-integration-module MODULE=adapters/adapter-transport-smb
```

Full reactor acceptance uses `make verify`, including bootstrap composition.
Deterministic suites run without SMB credentials or a provisioned server. Their
fakes prove adapter decisions, not server ACLs, real rename/share-mode atomicity,
wire encryption, network failure behavior or SDK shutdown latency. The positive
live encryption test is not a negative downgrade test; fail-closed session checks
are separately exercised in `SmbjShareClientFactoryTest`.

External suites skip by default. Record actual executed/skipped cases and the
server/version/target policy with qualification evidence; a property-gated skip
or one successful server run does not close [OPS-8](../../../../../../../../../../../docs/KNOWN-ISSUES.md#ops-8)
for another target. Follow [TESTING.md](../../../../../../../../../../../docs/TESTING.md) for discovery and evidence rules.

## External fixture and commands

Use a dedicated test share/directory. Common nonsecret properties are
`ioc.smb.host`, `ioc.smb.port` (default 445), `ioc.smb.share` and
`ioc.smb.encryption` (default `required`). These are test fixture properties,
not application configuration keys.

Credentials are read directly from environment, never Maven `-D` properties
retained in Failsafe reports:

| Fixture | Required environment pair | Optional domain |
|---|---|---|
| Basic import/notify/encryption | `SMB_USER`, `SMB_PASSWORD`; fallback to a complete service pair only when the standard pair is absent | `SMB_DOMAIN`, or service domain for the fallback |
| Hardening service | `SMB_SERVICE_USER`, `SMB_SERVICE_PASSWORD` | `SMB_SERVICE_DOMAIN` |
| Hardening producer | `SMB_PRODUCER_USER`, `SMB_PRODUCER_PASSWORD` | `SMB_PRODUCER_DOMAIN` |

A partially configured pair fails rather than mixing identities. Supply secrets
through the execution environment without echoing them into shell logs.

The basic import suite provisions only its UUID subdirectories below
`ioc.smb.remotePath` and removes those trees in `finally`. Notify suites watch
that root directly, create uniquely named files there and delete those exact
files. The fixture identity therefore needs the corresponding create/read/write/
rename/delete rights, in addition to watch/list access. They are functional tests,
not proof of least-privilege producer isolation.

```bash
./mvnw -B -ntp -pl adapters/adapter-transport-smb -am \
  -Dskip.unit.tests=true \
  -Dit.test=SmbManagedImportContractIT,SmbChangeNotifyContractIT \
  -Dfailsafe.failIfNoSpecifiedTests=false \
  -Dioc.smb.contract=true \
  -Dioc.smb.host=files.example.test -Dioc.smb.port=445 \
  -Dioc.smb.share=ioc-test -Dioc.smb.encryption=required \
  -Dioc.smb.remotePath=contract-tests verify
```

The hardening suite needs an otherwise empty dedicated producer inbox with all
four private directories already provisioned and producer access denied.
It does not create or remove the namespace; it creates its own candidate/probe
objects and cleans up exact files. Its detection selects the first candidate,
so unrelated files must not be present. Configure server-side ACLs and both
identities as described in the
[managed-import guide](../../../../../../../../../../../docs/guides/dataframe-import.md). The automated denial checks
exercise private listing and file creation; verify the full traversal/read/write/
delete denial matrix separately on the target.

```bash
./mvnw -B -ntp -pl adapters/adapter-transport-smb -am \
  -Dskip.unit.tests=true -Dit.test=SmbManagedImportHardeningContractIT \
  -Dfailsafe.failIfNoSpecifiedTests=false \
  -Dioc.smb.hardening.contract=true \
  -Dioc.smb.host=files.example.test -Dioc.smb.port=445 \
  -Dioc.smb.share=ioc-test -Dioc.smb.encryption=required \
  -Dioc.smb.hardening.remotePath=hardening/inbox verify
```

For the positive encrypted-session contract, the standard/fallback identity needs
list access to an existing test directory; the suite does not publish a file:

```bash
./mvnw -B -ntp -pl adapters/adapter-transport-smb -am \
  -Dskip.unit.tests=true -Dit.test=SmbEncryptionContractIT \
  -Dfailsafe.failIfNoSpecifiedTests=false \
  -Dioc.smb.encryption.contract=true \
  -Dioc.smb.host=files.example.test -Dioc.smb.port=445 \
  -Dioc.smb.share=ioc-test -Dioc.smb.encryption=required \
  -Dioc.smb.remotePath=contract-tests verify
```

Run these commands only with the stated fixture provisioned. Offline validation
can check their syntax/selector wiring but cannot supply a live-server result.
