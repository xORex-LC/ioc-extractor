# Closed-input configuration recipe fixture

[ConfigurationRecipeMain.java](ConfigurationRecipeMain.java) is a synthetic
consumer for the code example in
[config-spring-boot-check.md](../../../../../../../docs/guides/libraries/config-spring-boot-check.md).
[ConfigurationCheckRecipeIT](../../../java/com/iocextractor/bootstrap/ConfigurationCheckRecipeIT.java)
extracts the guide source/YAML, compiles this driver and forks it with library/
third-party classes while excluding IOC application classes.

The driver checks explicit overrides, complete-list replacement, binding/name/
input-policy failures, value-free exceptions, immutable returned data and repeated
check isolation. It deliberately supplies ambient input and a component trap;
the closed-input check must neither read that ambient configuration nor scan
runtime components. This differs from the IOC semantic checker, which retains
effective Boot ConfigData sources.

Keep the guide block, fixture and expected behavior aligned. No real secrets or
operator config belong here. Local JVM/compiler qualification does not establish
cold Maven repository consumption. Run from the repository root:

```bash
make test-one MODULE=bootstrap/ioc-app TEST=ConfigurationCheckRecipeIT
```
