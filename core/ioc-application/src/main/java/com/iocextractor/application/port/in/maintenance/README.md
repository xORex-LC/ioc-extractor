# Ordinary retention driving port

## Purpose and boundary

One explicit sweep of configured ordinary retention targets, separate from protected import/slice/lifecycle cleanup.

## Structure

| Entry points / files | Responsibility |
|---|---|
| [RunRetentionUseCase](RunRetentionUseCase.java), [RetentionResult](RetentionResult.java) | Drive a sweep and return scanned/reaped/failed counters. |

## Contracts and maintenance

Runtime selects schedule and validated targets. The result summarizes the sweep; it does not grant permission to use a leaf-file reaper on protected import units, canonical databases or delivery-pinned slices. Physical safety belongs to the driven store. Shared age/count policy operates on the unit chosen by the owning capability.
[Module map](../../../../../../../../../README.md) owns dependencies and verification; exact member guarantees remain in Javadoc.
