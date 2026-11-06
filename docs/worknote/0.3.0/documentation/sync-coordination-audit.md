---
title: "DOC-02F — Sync и событийная координация"
version: "0.3.0"
goal_id: "R030-DOC"
status: "Verified documentation slice with open source defects"
document_type: "Execution evidence"
source_of_truth: false
language: "ru"
---

# DOC-02F — Sync и событийная координация

Проверка 2026-11-06 (Asia/Irkutsk), HEAD
`db1e5f38b3d585ad3359f646a432b7374172c1cb`, ветка `docs/quality`.
Входное дерево чистое. Контракт: [R030-DOC](../goals/R030-DOC-documentation.md).

Scope: dev/sync, dev/event-coordination и operator remote-storage-sync — три
EN/RU-пары. Русские canonical dev pages переработаны в English, добавлены их
RU-пары; существующая пара guide согласована. Проверялись корректность, полнота,
примеры и ownership. Применены code-documenter, java-code-review, cli-developer,
spring-boot-engineer. Полный аудит module README, библиотечных API и root security
policy сюда не входит. Production/test Java, POM, scripts и quality policy не менялись.

На входе snapshot 361 tracked Markdown/TSV paths. Предыдущие содержательные
DOC-02A–E сохранены; общие navigation/status/inventory обновлены. KNOWN-ISSUES
дополнен ровно OPS-11/12; удаление этих двух строк восстанавливает исходные байты,
включая TOOL-10 и OPS-10. Нового коммита нет.

## Находки и решения

| ID | Приоритет / наблюдение | Результат |
|---|---|---|
| `DOC-S01` | H: fetch identity не имеет endpoint/source namespace | Реальные use case + JDBC воспроизводят skipped второго endpoint без GET. Новый OPS-11; ограничение указано во всех затронутых EN/RU docs, source fix не заявлен |
| `DOC-S02` | H: occupied-name check отделён от ATOMIC_MOVE | Два concurrent endpoint с разными tuples и одним leaf: два FETCHED, один inbox file на Linux. Новый OPS-12; атомарная видимость отделена от no-replace ownership/fencing |
| `DOC-S03` | M: re-upload безусловно объявлялся новым объектом | Identity — path/size/mtime, не content hash; повтор под прежним tuple может остаться невидимым независимо от изменённых байтов |
| `DOC-S04` | M: debounce/exclude предлагались как компенсация незавершённой записи | Описаны обязательные close/rename producer, отсутствие remote quiet-period и before/after stat; поздний полный download не отменяет уже ingested partial content |
| `DOC-S05` | M: polling обещал найти всё, а события — восстановить любую потерю | Гарантия ограничена eligible retained inputs и правильным idempotency key; transient/deleted-before-listing objects не восстанавливаются из lossy hints |
| `DOC-S06` | M: publish marker/atomicity скрывали точный порядок и проверку байтов | Upload marker последним внутри staging, size verification, final rename и marker check описаны отдельно; matching-marker recovery не хеширует заново remote CSV, consumer проверяет manifest/files |
| `DOC-S07` | M: retention ожидал только успешную доставку и не учитывал конфигурационные изменения | Терминальный ABANDONED снимает pin без доставки; missing/nonterminal pairs pin, удаление target меняет obligation. Публичного abandon/requeue CLI нет; отключение sync/publish снимает delivery-aware veto, что отражено как операционная граница |
| `DOC-S08` | M: max-attempts трактовался как число повторов, все network failures как immediate retry | Initial call включён; только TRANSIENT→RETRY_NOW. UNREACHABLE/RESOURCE_EXHAUSTED→RETRY_LATER, FAIL-категории без micro-retry, watch reconnect имеет отдельный lifetime |
| `DOC-S09` | M: CLI syntax-схемы с квадратными скобками выдавались за runnable commands; sync all мог читаться как полный ETL | Даны installed sudo commands. all preflights обе selections, затем fetch/publish existing slices, без ingest/export между ними; отдельная JVM не разделяет daemon lane |
| `DOC-S10` | M: dry-run можно было принять за отсутствие любого I/O и проверку publish ACL | Реальная composition создаёт service DB/schema, не создавая inbox или ledger rows. Fetch dry-run list-only, publish dry-run local-only; remote write permissions не проверяются |
| `DOC-S11` | M: transient transport и durable failed pair смешивались в health | Уточнены source/target UNKNOWN, sync aggregate DOWN при durable FAILED, reconnect grace, UP-only health exit 0 и process-owned SMB telemetry против server-wide usage |
| `DOC-S12` | M: Samba consumers имели group write, NTFS grant не снимал inherited write | Output group mode изменён на read/traverse; Windows пример ограничен fresh root с сохранением SYSTEM/Admin access и снятием inherited broad grants. Обязательна отдельная negative qualification |
| `DOC-S13` | M: Samba inline comments и command fragments были непроверяемыми templates | Комментарии отделены от values, полный YAML прошёл semantic checker; Linux/Samba flags сверены с локальным client и official docs; Windows runtime evidence не выдуман |
| `DOC-S14` | M: event doc называл artifact_revision insert-driven и экспорт только после new rows | Public-field mutation может увеличить revision без нового lifecycle; expiry и TTL-only renewal не увеличивают public-data revision и не обходят export gates |
| `DOC-S15` | M: event/backstop guarantees не отделяли partial dispatch/Error и межпроцессность | Сохранены precise publisher/observer contracts, добавлены producer-specific post-commit RuntimeException handling, escaping Error после commit и process-local admission scope |
| `DOC-S16` | L: два dev docs оставались русскими canonical без RU paths; guide pair имел literal drift | Канонические pages English, полные RU-пары и навигация согласованы; headings/blocks/identifiers/normalized links сверены без ручного перевода code comments |

## Живые источники

Source set `sync` дополнен точными entry points и suites в
[source-sets.tsv](source-sets.tsv).

| Граница | Источник | Свидетельство / предел |
|---|---|---|
| Remote identity / inbox landing | RemoteObjectIdentity, RemoteSourceMonitor, RemoteFetchService, JdbcRemoteFetchLedger и service v6 DDL | Реальные JDBC reproductions OPS-11/12; existing tests проверяют последовательные cases, не доказывают отсутствие этих гонок |
| Publish saga и retention | ArtifactPublishService, PublishLedgerSliceRetentionGuard, JdbcPublishLedger, SmbFileTransport | Existing marker recovery/mismatch, retry timeout, terminal guard и fake-wire tests; remote consumer readback не подменён проверкой одного marker |
| Retry / transport policy | Retrier, RemoteErrorKind, SmbExceptionMapper, endpoint settings/session factories | Enum dispositions и raw resource NTSTATUS, encryption policy, timeout scope; external encryption suite offline skipped |
| Detection / scheduling / health | SyncConfig, RemoteFetchDetectionCoordinator, listeners, DaemonPublishScheduler, watch lifecycle и health indicators | Per-source coalescing, endpoint lanes, durable failed pair, watch grace; server-wide limits не видны из protocol |
| Events / canonical mutations | EventCoordinationConfig, SpringControlEventPublisher, canonical/import event decorators | Partial delivery, primary/suppressed failures, fatal Error boundary и mutation-vs-TTL revisions; библиотечный API не объявляется полностью пересмотренным |
| CLI / config | SyncFetch/Publish/AllCommand, SyncSelectionCatalog, shipped launcher, application.yml | Valid/invalid selectors до I/O; full Boot composition, schema initialization и no-op work отделены от source/remote qualification |
| Server examples | Samba smb.conf/smbclient manuals, Microsoft icacls/New-SmbShare | Документальные references и local client load; нет нового server provisioning, negative ACL или Windows execution evidence |

## Reproductions OPS-11 и OPS-12

Локальный `.dev/r030-doc-02f/FetchBoundaryProbe.java` компилируется JDK ToolProvider
с текущим reactor classpath и вызывает production RemoteFetchService с production
JdbcRemoteFetchLedger. Из service v6 SQL создаётся настоящая SQLite schema; transport
заменён детерминированным FileTransport. Данные и все файлы синтетические.

- OPS-11: source one/endpoint one получает файл; source two/endpoint two с тем же
  path/size/mtime и другими bytes получает skipped=1, GET(two)=0. Endpoint отсутствует
  и в identity record, и в JDBC primary key, и в process-local in-flight key.
- OPS-12: два endpoint имеют разные tuples (размеры 4/5), один final leaf; latch
  удерживает оба GET после выбора свободного имени. После release оба возвращают
  fetched=1, JDBC содержит две FETCHED rows, но в inbox один regular file. Один
  payload заменён; это риск потери input, а не только лишний transport retry.
- SQLite/файлы настоящие, OS Linux; SMB wire и daemon scheduling не участвовали.
  Latches, future timeouts, finally shutdown и bounded join не оставили workers.
- Исправления требуют namespace/migration и no-replace ownership решений с
  regression tests и полными gates. Этот документационный пакет их не реализует.

## Проверка

Evidence находится в ignored `.dev/r030-doc-02f/`:

- `make context`, `make doctor-core`: clean input, HEAD/branch и JDK 21 подтверждены.
- Выбранный Surefire cohort: **195 tests / 25 suites, 0 failures/errors/skips**.
  Application sync use cases, CLI selectors/execution, detection/listeners,
  scheduling/health, SMB settings/errors/telemetry/watch/session factories и
  Spring event publisher. Список селекторов и результаты — `unit.log`.
- Выбранный Failsafe cohort: **34 reported / 31 executed / 3 external skips**,
  9 suites, 0 failures/errors. JdbcRemoteFetchLedger (3), JdbcPublishLedger (11),
  SmbFileTransport (10), SpringControlEventPublisher (4), SpringCommandConstruction
  (1), SyncEnabledDaemonRuntimeMode (1), NativeDeliveryLifecycle (1).
  SmbEncryptionContract (1 skip) и SmbChangeNotifyContract (2 skips) не являются
  live transport evidence. Лог `integration.log`.
- Дополнительные **13 checks**: DocumentationConventionTest (1),
  ConfigurationDocumentationContractTest (7), SyncSelectionCatalogTest (5);
  `documentation-contract.log`, PASS.
- Полный YAML block прошёл реальный configuration-only semantic checker с
  `CONFIG.SEMANTIC_VALID`; runtime DB при этом не создавались.
- **12 CLI JVM**: 6 literal invocations и 6 дополнительных semantic/help/version/
  invalid-selection cases. Три dry-run проходят actual Boot/Picocli/use-case/JDBC
  composition с primary FileTransport fixture; SMB wire не исполнялся.
  Fetch/all получают один list, get/remote writes=0; publish не делает remote I/O.
  Invalid all selection даёт exit 1 до transport. Health использует собственный
  loopback HTTP fixture. Флаг не создаёт inbox/business delivery rows, но создаёт
  настоящую service DB/schema, что явно отражено в guide. Secret sentinel не утёк.
- Все **4 bash blocks** прошли `bash -n`; privileged provisioning/query commands
  проверены argument-only wrappers без изменения host. Samba client 4.19.5 загрузил
  INI, распознал encryption/CLI options и получил ожидаемый CONNECTION_REFUSED на
  собственном закрытом loopback port. Успешное соединение/server ACL не заявляются.
- PowerShell/pwsh отсутствует: commands сверены по official references и смыслу
  свежего ACL tree; AST, native icacls, effective rights и Windows runtime не
  проверены. `testparm`/Samba server тоже не установлены: server config validation
  и negative tests остаются target qualification, не локальным PASS.
- Три EN/RU-пары имеют одинаковые heading levels, exact fenced blocks,
  inline identifier sets и normalized link-target multisets.
- Итоговый inventory: **362 Markdown paths / 64 source sets**, 57 отсутствующих
  RU-пар (root 3, capability 3, ADR 49, guide 2). Current/base hashes, прежние
  документы и protected source/history/floors сверены. `make docs`: **2712 links /
  671 unique / 2484 OK / 0 errors / 228 excluded**. `git diff --check`: PASS.

## Границы и дальнейшая работа

Доработан документационный комплект, не весь R030-DOC. Новых live SMB/Windows,
server ACL, systemd/deployment, backup/restore или load results нет. Fixtures
и workers завершены; runtime DB остаются только в ignored sandbox.

OPS-11/12 требуют отдельного source fix: их влияние — пропуск или замена источника.
TOOL-10, OPS-10, ING-11/13, OPS-3/5/6/8 и остальные owning obligations не закрыты.
Стандартные зелёные suites не опровергают отдельно воспроизведённые defects.
Полные verify/PMD не запускались: их последний PASS относится к
`191e74146dddeeab36bf59d9d09020a92bef0620`, оба freshness flags false.
