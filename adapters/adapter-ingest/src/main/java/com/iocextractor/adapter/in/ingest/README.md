# adapter.in.ingest

Файловый inbound-adapter daemon-режима.

## Ответственность

- обнаруживает стабильные source-файлы в `inbox`;
- реализует local managed-import complete listing, strict ownership и immutable snapshots;
- считает content hash и передаёт файл в `IngestSourceUseCase`;
- реализует физический lifecycle `inbox -> processing -> done|failed`;
- хранит durable file-ledger для компенсации после рестарта.
- после retry exhaustion выполняет reject и доставляет typed `INGEST.*`
  diagnostic ровно один раз.
- планирует positive retry backoff без блокирующего sleep poller thread;
- воспринимает WatchService только как source-level doorbell; filename event не
  является authority, polling выполняет тот же complete listing.
- наблюдает recovery-before-intake barrier через typed `ingest_recover` logs и
  доставляет недоставленный startup `INGEST.*` diagnostic ровно один раз;
- помечает duplicate terminal `source_ingest` полем
  `ioc.ingest.disposition=duplicate`.

## Границы

- не содержит IOC extraction logic;
- не знает CSV schema и JDBC schema policy;
- не назначает stable id;
- не управляет CLI lifecycle.
