# Test lifecycle verification

This directory owns the JDK-only integrity check for the repository test
lifecycle. It is build support, not a Maven reactor project.

`TestLifecycleVerifier` reconciles every reactor `src/test/java` suite against
the Surefire/Failsafe naming contract, rejects tags outside the accepted
taxonomy, checks composed-annotation semantics and verifies that a full Maven
`verify` produced exactly one report for every selected suite in the expected
engine.

`test-lifecycle.properties` is the reviewed source-universe ratchet. Update its
counts deliberately in the same change that adds, removes or reclassifies test
suites. A same-count replacement still requires normal code review because the
file is an integrity count, not a semantic test inventory.

The root `validate` phase compiles the verifier and runs its synthetic contract
harness before child projects. The aggregate coverage module runs the late
report-union check after all production modules have completed Failsafe.

Run the source/convention gate alone with:

```bash
./mvnw -B -ntp -N validate
```
