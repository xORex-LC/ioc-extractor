# adapters/adapter-ingest

## Назначение

Inbound file-ingestion adapter for daemon mode. Owns Spring Integration file
watch/poll wiring, filesystem source lifecycle, file-backed ingestion ledger and
retry/dead-letter side effects.

**Правило слоя:** translates filesystem events into `IngestSourceUseCase` calls;
it does not implement IOC extraction rules and does not write canonical
artifacts directly.

## Структура

| Подпапка / файл | Назначение |
|---|---|
| `pom.xml` | Maven module descriptor |
| `src/main/java/com/iocextractor/adapter/in/ingest/` | Spring Integration flow and filesystem adapters |
| `LocalManagedImportSourceLifecycle` | Strict local claim, producer-stability proof and immutable snapshot materialization |
| `LocalImportChangeSignalSource` | Optional WatchService doorbell that discards event filenames |

## Зависимости

**Зависит от:** `ioc-application`, platform errors/diagnostics/observability/
concurrency, Spring Integration file support.

**Не импортируется:** domain internals, concrete CSV sink internals, bootstrap.

## Инварианты

- `iocIngestionFlow` не стартует автоматически. `IngestionStartupCoordinator`
  сначала восстанавливает run ledger, затем source ledger, выполняет common
  canonical lifecycle admission и только после этого открывает intake; любая
  ошибка оставляет flow остановленным. Coordinator имеет `ApplicationRunner`
  order `HIGHEST_PRECEDENCE`. Lifecycle policy/SQL остаются за application/JDBC;
  этот driving adapter знает только admission port.
- Все application entry points для одного content `SourceKey` используют общий
  synchronous keyed guard. File ledger отдельно сериализует read/decide/replace
  внутри одного adapter instance; сервисы над общими namespace/ledger обязаны
  разделять guard, cross-process coordination не заявлена. Release-инвариант не
  маскирует primary work failure: secondary failure становится suppressed.
- Source-ledger terminal transitions монотонны: same-target retry идемпотентен,
  opposite-target transition конфликтует и не переписывает победителя.
- Каждая принятая file delivery получает новый UUID `ObservationId`; один и тот
  же id сохраняется через handler retry и terminal reject. Processing filename
  и ledger key включают observation identity, поэтому одинаковые bytes из двух
  доставок не перезаписывают друг друга. Старые content-keyed ledger/files
  читаются как `legacy:<sourceKey>` для recovery.

- `FileSourceMessageHandler` владеет final retry boundary: после исчерпания
  попыток он выполняет reject/dead-letter transition, если use case ещё
  не вернул durable `FAILED`, затем эмитит один typed `INGEST.*` diagnostic.
- Content hashing входит в тот же bounded retry/backoff. Если содержимое
  прочитать нельзя, handler использует fingerprint `path+size+mtime` только как
  terminal ledger identity и один раз эмитит `INGEST.SOURCE_UNREADABLE`.
  Повторный poll того же durable `FAILED` завершается тихо; физический
  pre-claim quarantine остаётся отдельным ING-13.
- После структурно завершённой extraction handler публикует terminal
  `source_ingest` с durable run id, completion и отдельными severity counts.
  `COMPLETED_WITH_ERRORS` имеет `event.outcome=failure`; no-ETL receipt replay не
  получает вымышленный extraction completion и помечается
  `ioc.ingest.disposition=duplicate`.
- `IngestionStartupObserver` публикует одну операцию `ingest_recover`: start и
  один terminal outcome с duration/counts или safe error type. Он доставляет
  ещё не выпущенный `INGEST.*` carrier на startup boundary, но не дублирует
  `INGEST.RECOVERY_FAILED`, уже выпущенный application recovery.
- Локальный error-log не дублирует canonical diagnostic delivery.
- `StrictAtomicFileOwnership` — общий fail-closed primitive обычного и managed
  intake: только regular non-symlink source, private target, no-replace и без
  copy/non-atomic fallback. Managed intake дополнительно revalidate-ит stable
  candidate после claim и фиксирует read-only snapshot с SHA-256/size/fsync.
- Positive retry backoff обычного ingest планируется на daemon scheduler;
  poller thread больше не удерживается через `Thread.sleep`.
