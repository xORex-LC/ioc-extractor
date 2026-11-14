# Ordinary retention store

## Purpose and boundary

List/reap configured ordinary entries without coupling pure selection to filesystem implementation.

## Structure

| Entry points / files | Responsibility |
|---|---|
| [RetentionStore](RetentionStore.java) | Listing and delete/archive effects for the owning target. |

## Contracts and maintenance

The target root and entry identity constrain physical operations. Store implementations own root/symlink/recursive traversal/archive safety and errors; the policy only selects units. Do not use this leaf-entry contract as a substitute for protected import or delivery-aware slice retention. Those capabilities own their evidence/deletion order and distinct stores.
[Module map](../../../../../../../../../README.md) owns dependencies and verification; exact member guarantees remain in Javadoc.
