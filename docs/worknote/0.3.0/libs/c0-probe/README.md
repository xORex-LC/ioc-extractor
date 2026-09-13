# C0 configuration characterization probe

This local research fixture compares current name inspection/source reporting
with Spring Boot binding under an unrelated `collector` schema. It is not a
future service configuration, Maven module, production implementation or admitted
library test suite. The [C0 analysis](../configuration-c0-analysis.md) interprets
the observations and distinguishes limitations from defects.

## Run

From the repository root, with a full JDK 21 available through `JAVA_HOME`:

```bash
make doctor-core
make test-one MODULE=bootstrap/ioc-app TEST=IocConfigurationOverrideReporterTest
python3 docs/worknote/0.3.0/libs/c0-probe/run.py
```

The facade run supplies the resolved dependency paths. The probe uses only
selected third-party JARs from that report, never reactor output classes. Boot
4.0.8 is pinned for this characterization. A version change requires requalification.
No dependency downloads are performed by the Python probe itself.

`run.py` compiles temporary copies of five existing source files. The only
substitutions are the `collector` prefix and the selected synthetic root schema.
It does not alter the algorithms or compile the real `IocProperties`. The unchanged
migration catalog is an incidental reporter compilation dependency; logging and
product migration handling are not invoked.

`ConfigurationContractProbe.java` supplies synthetic values and checks the name
preflight, Binder and reporter independently. The value-read trap throws if the
scanner/reporter accesses a property value. Temporary class files are removed
after execution. Generated evidence is written to `target/configuration-c0/`.

`expected.tsv` is the reviewed 54-case observation snapshot, including known
limitations. `baseline.json` records original-source and dependency hashes and
the run's Java/commit identity. Matching the snapshot means reproduction, not
that a generic library has passed admission. Do not refresh it automatically to
conceal changed behavior; inspect and explain any difference.

These files are part of the local release worknote. They do not change Surefire,
Failsafe, analyzer or coverage scope. C1 should replace selected characterization
cases with supported-behavior tests of the actual extracted API.
