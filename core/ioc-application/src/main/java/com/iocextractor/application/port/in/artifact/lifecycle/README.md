# Lifecycle driving ports

This package exposes small framework-free use-case boundaries for lifecycle
admission, expiration reconciliation, mutable projection convergence and
history retention.

Spring scheduling and CLI concerns stay outside this package. Each operation
re-reads durable state; local events and admission callbacks are latency hints,
not correctness authorities.
