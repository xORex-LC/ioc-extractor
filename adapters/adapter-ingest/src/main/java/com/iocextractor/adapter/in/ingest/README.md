# adapter.in.ingest

Файловый inbound-adapter daemon-режима.

## Ответственность

- обнаруживает стабильные source-файлы в `inbox`;
- считает content hash и передаёт файл в `IngestSourceUseCase`;
- реализует физический lifecycle `inbox -> processing -> done|failed`;
- хранит durable file-ledger для компенсации после рестарта;
- поддерживает `AGGREGATED` как checkpoint, который выставляет stage 11
  aggregation use case после успешной записи канонических артефактов.

## Границы

- не содержит IOC extraction logic;
- не знает CSV schema и partition path policy;
- не агрегирует партиции и не назначает stable id;
- не управляет CLI lifecycle.
