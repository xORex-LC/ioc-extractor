# Historical classification package marker

## Purpose and boundary

This package contains only `package-info.java`; executable shared indicator classification lives in `core/ioc-processing`.

## Structure

| Entry points / files | Responsibility |
|---|---|
| [package-info](package-info.java) | Historical package description, not a second classification implementation. |

## Contracts and maintenance

Follow [ioc-processing](../../../../../../../../ioc-processing/README.md) and the selected document/import processing plan. Do not add a duplicate classifier here or infer an active implementation from the package name. Removal of this historical namespace is a separate cleanup decision.
[Module map](../../../../../../../README.md) owns dependencies and verification; exact member guarantees remain in Javadoc.
