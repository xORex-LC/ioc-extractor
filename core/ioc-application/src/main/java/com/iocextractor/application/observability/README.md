# application/observability

## Назначение

Application-owned compact representations of already-computed pipeline
decisions. They carry trusted in-process facts to an outbound operational trace
port without importing SLF4J or the logging taxonomy.

## Контракты

- decision objects are built only after `PipelineDecisionTracer.isEnabled()`;
- records reuse domain/application outcomes and never trigger domain work;
- raw values remain inside the trusted process boundary; rendering and query
  redaction belong to the outbound adapter.
