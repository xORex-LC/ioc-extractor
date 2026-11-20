# Regex engine contract tests

Shared behavioral checks for the [production package](../../../../../../../main/java/com/iocextractor/adapter/out/regex/README.md).

| Suite | Observable contracts |
|---|---|
| [PatternEngineContractTest.java](PatternEngineContractTest.java) | Stable engine IDs, reusable compilation, ordered half-open spans, empty results and lazy UTF-16 cursor behavior on both engines |

Three parameterized methods run once for RE2/J and once for JDK. The suite is
Surefire-discovered and carries the TCK's `@ContractTest` (`contract`) annotation;
its executable assertions are local rather than an inherited TCK base class.
Fixtures are inline, with no Spring context or resource tree.

The cursor fixture supplies a custom CharSequence whose `toString()` throws.
It checks two matches after a supplementary character, exact absolute UTF-16
offsets/full values and exhaustion. The batch fixture also uses StringBuilder,
reuses the same compiled pattern across inputs and asserts independent expected
spans. These checks establish representative port behavior without accepting a
whole-input copy as lazy matching.

The local suite does not establish universal regex equivalence, unsupported
syntax/error contracts, null/state boundaries, zero-width/overlapping behavior,
concurrent load or ReDoS/time/memory limits. It does not apply IOC detection
priority, refang rules, span claiming or source attribution.

Bootstrap's [PatternEngineConfigurationTest](../../../../../../../../../../bootstrap/ioc-app/src/test/java/com/iocextractor/bootstrap/PatternEngineConfigurationTest.java)
separately checks typed default/JDK selection and the classpath detection and
section-marker patterns on both engines with positive/negative fixtures. That
corpus qualifies those configured patterns, not every operator override or full
Java regex feature. It runs in its owning module during the full reactor gate.

Run from the repository root:

```bash
make test-module MODULE=adapters/adapter-regex-re2j
```

There are no local Failsafe suites. Full acceptance uses `make verify`;
[TESTING.md](../../../../../../../../../../docs/TESTING.md) owns discovery/contract-evidence rules and
[processing internals](../../../../../../../../../../docs/dev/processing.md) explains the bounded
document path above this regex boundary.
