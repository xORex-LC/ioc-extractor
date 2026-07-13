# adapter.in.ingest

Файловый inbound-adapter daemon-режима.

## Ответственность

- обнаруживает стабильные source-файлы в `inbox`;
- считает content hash и передаёт файл в `IngestSourceUseCase`;
- реализует физический lifecycle `inbox -> processing -> done|failed`;
- хранит durable file-ledger для компенсации после рестарта.
- после retry exhaustion выполняет reject и доставляет typed `INGEST.*`
  diagnostic ровно один раз.

## Границы

- не содержит IOC extraction logic;
- не знает CSV schema и JDBC schema policy;
- не назначает stable id;
- не управляет CLI lifecycle.
