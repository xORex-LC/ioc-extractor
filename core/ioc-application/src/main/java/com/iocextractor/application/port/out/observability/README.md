# port/out/observability

## Назначение

Outbound application boundary for structured per-item pipeline traces.

## Контракты

- producer checks `isEnabled()` before allocating trace DTOs or derived strings;
- the implementation performs the final gate again before render/emission;
- tracing is observational and does not participate in `FailurePolicy`,
  diagnostics or completion status.
