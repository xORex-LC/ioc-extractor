# Import mapping tests

Atomic logical row, header alias and field-merge expectations.

| Entry points / files | Responsibility |
|---|---|
| [DataframeImportRowMapperTest](DataframeImportRowMapperTest.java) | Admitted transformations, validators, processed session/fan-out and final keys/authority. |
| [ImportHeaderPlanTest](ImportHeaderPlanTest.java), [ImportMergeResolverTest](ImportMergeResolverTest.java) | Header positions and ABSENT/NULL/VALUE distinctions under supported merge policies. |

Use independent expected cells/actions and negative row outcomes. Do not use current mapper output as its own oracle. Fake processed preparation cannot replace selected Camel/CSV/JDBC integration evidence; every strategy must preserve source authority and quota/validation accounting.

[Test map](../../README.md) owns commands/evidence limits; [TESTING.md](../../../../../../../../../../docs/TESTING.md) owns lifecycle and test quality.
