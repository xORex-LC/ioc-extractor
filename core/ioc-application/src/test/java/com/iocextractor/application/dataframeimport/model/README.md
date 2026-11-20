# Import model tests

Constructor/state/equality and opaque-token boundaries without transport or storage.

| Entry points / files | Responsibility |
|---|---|
| [DataframeImportModelContractsTest](DataframeImportModelContractsTest.java) | Typed policy, delivery/transition, limits, safe carriers and integrity evidence. |
| [ImportCellTest](ImportCellTest.java), [ImportManagedObjectIdTest](ImportManagedObjectIdTest.java) | Tri-state cell meaning and stable transport-neutral managed token. |

Model acceptance is not authenticity: an imported public ID is not canonical authority and a constructed snapshot/receipt value still needs owning-adapter verification. Keep safe failure/status values free of raw cells; concrete parser exceptions have their own boundary.

[Test map](../../README.md) owns commands/evidence limits; [TESTING.md](../../../../../../../../../../docs/TESTING.md) owns lifecycle and test quality.
