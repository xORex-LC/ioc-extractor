# Domain and concrete-adapter seam tests

These tests live in the application assembly module because they construct real
external integration adapters. They complement the framework-free domain tests;
Java package names here do not move the production domain into bootstrap.

| Suite | Responsibility |
|---|---|
| [attribute/MarkerSourceAttributorTest.java](attribute/MarkerSourceAttributorTest.java) | Section-marker attribution through the RE2/J engine |
| [classify/RuleBasedMatchPolicyTest.java](classify/RuleBasedMatchPolicyTest.java) | Feature extraction plus Guava PSL classification and the four mask-rule buckets |
| [extract/RegexIndicatorExtractorTest.java](extract/RegexIndicatorExtractorTest.java) | Extraction with the concrete regex integration |

Fixtures are small synthetic values and explicit rules. They qualify those
composed seams, not the entire operator configuration, parser corpus or every
PSL/regex behavior. The configured-pattern contract and end-to-end golden corpus
live in the [parent test map](../README.md).
Run them with `make test-module MODULE=bootstrap/ioc-app` from the repository root.
