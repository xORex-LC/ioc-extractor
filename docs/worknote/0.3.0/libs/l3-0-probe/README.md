# LIB-3 L3-0 characterization fixture

This fixture qualifies the extraction **contract**, not an implemented library.
It runs 62 Jupiter cases with an unrelated `collector.*` catalog, actual current
logging algorithms and the actual Boot/Logback encoder. It is outside the Maven
reactor and its test-report/coverage denominators. It is not a release gate or
an independently packaged consumer.

## Run

Use a full JDK 21. Prepare the resolved classpath with the existing focused test:

```sh
make doctor-core
make test-one MODULE=bootstrap/ioc-app TEST=LoggingControlEventObserverTest
python3 docs/worknote/0.3.0/libs/l3-0-probe/run.py
```

The runner reads that Surefire XML, selects third-party JARs only, and checks the
source/dependency SHA-256 identities against `baseline.json`. A changed source or
dependency stops the run; review and requalify instead of automatically refreshing
the baseline. The runner neither downloads dependencies nor includes reactor
class directories/JARs. The cached Jupiter launcher must also be available.

Temporary source copies receive only package/import changes and replacement of
the IOC `LogField` type with `ProbeField`. `ProbeField` is a deliberately simple
record with an unrelated catalog; its equality includes both key and type. The
normalization, scope, event and URL algorithms are otherwise unchanged. The
bootstrap encoder is compiled unchanged. A small local action enum only satisfies
the existing facade's source signature; no IOC catalog is compiled or loaded.

`target/logging-l3-0/tests.log` and `identity.json` retain the run summary, source
and dependency hashes, probe hashes, JDK and HEAD. The launcher fails if the case
count differs from 62, any case/container fails, or any case is aborted/skipped.
No retry is used. The worker-context case has a containing timeout, timed waits
and executor cleanup; backend fixtures stop their owned encoders/contexts.

## What the cases establish

- Scalar conversions/rejections, level-filter ordering and event/MDC precedence.
- Nested scopes, cleanup after body failure, explicit worker reconstruction,
  omission versus masking and the URL helper's literal delimiter behavior.
- Existing algorithms work with an unrelated catalog, but record equality alone
  permits duplicate wire keys with different types. Null removal also forgets
  the declared type. These are explicit L3-1 correction requirements.
- Boot output has nested typed fields; corrected control-event and informational
  skip representations can be encoded without borrowing ECS `event.type`.
- Current invalid classification/outcome/duration values pass through; current
  reserved metadata, dataset and scalar/object path collisions fail.
- Explicit `error.type` plus Throwable causes zero output bytes through a real
  appender and an internal error status. Cause-only and type-only paths work.
- The current dataset extension depends on mutable `LoggingEvent`; a
  `LoggingEventVO` does not receive it. Plain static Boot JSON addition also
  conflicts with dynamic `event.*` in the tested recipe.

Tests named `baselineDefect*` and other `baseline*` cases assert observed current
behavior, including defects. Their passing status is **not defect closure**.
The [fixed contract](../lib-3-logging-contract.md) identifies required corrections
and new API tests. Neither the new descriptor validation, full-mask method nor
ECS helper implementations exist yet. In particular, the masking test checks a
constant's representation, not a future helper that has not been implemented.

## Qualified versions and limits

Recorded on 2026-10-19, source HEAD
`662aa880587e11cea08204c250d89894abf1834c`, Java 21.0.11, SLF4J 2.0.18,
Boot 4.0.8, Logback 1.5.38, Jupiter 6.0.3 and AssertJ 3.27.7. All selected
transitive JARs are pinned by filename/hash in the baseline, including Spring
support needed by the Boot formatter. They are fixture dependencies, not the
proposed library's main dependency closure.

No broad provider/version compatibility, external collector, live Elasticsearch
mapping, new public API, publication, full-reactor gate or performance claim is
made. The fixture intentionally stops matching after production extraction:
migrate supported behavior into the new module's normal tests and keep Boot/IOC
regressions local. Do not change expectations merely to conceal an observed defect.
