# R0 Camel admission probe

Isolated test-only Maven experiment, outside the production reactor. No artifact
is installed or published. It checks the minimal embedded Camel path with explicit
Spring bean lifecycle, not Camel starter auto-configuration or the IOC application.

Run from the repository root:

```sh
./mvnw -B -ntp -f docs/worknote/0.3.0/data-processing-routing/qualification/r0/pom.xml clean test dependency:tree -Dverbose -DoutputFile=target/dependency-tree.txt
```

Evidence is regenerated in `target/surefire-reports/` and
`target/dependency-tree.txt`. Test dependencies are isolated here; the Boot parent
and relevant security overrides match the inspected root POM. The full reactor
dependency closure still requires R1 evidence after the adapter is introduced.

Two tests, bounded by JUnit timeout, cover startup, caller-thread execution,
failure propagation without retry, successful shutdown and invalid-route startup
rejection. Producers and contexts are closed by their owners. This probe does not
replace any production test, coverage or analyzer gate.
