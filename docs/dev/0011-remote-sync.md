# 0011 — Синхронизация с внешними хранилищами (delivery/SMB)

## Статус

**Проектирование, не реализовано.** Ветка `feature/delivery/smb`. Документ
фиксирует согласованные решения и открытые вопросы до начала реализации; код,
основные доки (`architecture.md`, новый `sync.md`/`ingestion.md`) и конфиг
обновляются по факту. Номер бэклога ING — TBD. **Hardening-проход после ревью завершён** (Findings F1–F5 + ref-1…9
закрыты): в 0011 — **F3** (доставка — отдельный `publish_ledger`, реш. 6), **ref-9** (publish
byte-preserving, реш. 17), **F2** (slice-retention, реш. 18); остальное — в
[0012](0012-streaming-dataframe-emission.md).

## Контекст

Нужна двунаправленная синхронизация с внешними хранилищами:

1. **Вход:** забрать файлы с IOC с удалённого хранилища и положить в `var/inbox`;
   дальше приложение отрабатывает штатно (существующий демон-инжест).
2. **Выход:** после прогона по конвейеру и формирования датафреймов — отправить
   их на удалённое хранилище. Хост/путь/транспорт на выходе **независимы** от
   входа.

Принципы (от постановки):

- **Сервис синхронизации не зависит от транспорта.** Транспорт внешний, его цель —
  доставить файлы; ему не нужно знать, какой он. Вся специфика транспорта — в
  стороне. Сейчас транспорт один (SMB), в будущем возможны другие.
- **Параметры подключения и пр. — через конфиг** (`application.yml`/`IocProperties`),
  не хардкод.

Как ложится на текущую систему: вход переиспользует уже готовый стриминговый
демон-инжест ([0001](0001-streaming-ingestion.md)) — его работа заканчивается,
когда файл лёг в `var/inbox`. Выход увозит **сформированные** артефакты; *как*
они формируются над непрерывным потоком (окна/триггеры/output-mode, immutable-
снимки) — отдельное cross-cutting направление в
[0012](0012-streaming-dataframe-emission.md). Доставка к этому **агностична**:
шлёт то, что сформировано (полный снимок или инкремент).

## Решения

**1. Корень конфига — `ioc.sync`** (двунаправленно: `fetch` + `publish`) с общими
именованными `endpoints` (эндпоинт = транспорт + параметры подключения + базовый
путь). Имя честнее «delivery» (фича двунаправленная). Вход и выход ссылаются на
эндпоинты по имени, поэтому хост/транспорт у них независимы.

**2. Транспорт-агностичность = порт.** SPI транспорта живёт в `application`
(`port/out/sync`: **stateless `FileTransport`**; `RemoteSession`/соединение —
внутренний тип адаптера, см. решение 13), вся специфика SMB — в
адаптере-модуле `adapters/adapter-transport-smb` на **smbj** (чистый Java SMB2/3,
без SMB1). Правило «один внешний lib = один модуль»: новый транспорт (SFTP/S3/…)
= новый `adapter-transport-*` за тем же портом, **без правок use-case** (OCP).
Типы smbj **не утекают в `core`** (закрепить ArchUnit). Выбор транспорта по id
(`transport: smb`) через реестр в bootstrap — как `ioc.engine` → `PatternEngine`.

**3. Два use-case в `application/sync`** (чистые, без Spring):
- `RemoteFetchUseCase` — вход: листинг источника → скачивание в **существующий**
  `ioc.ingestion.dirs.inbox`. Пайплайн **не дублируется**; работа fetch
  заканчивается на «файл атомарно лёг в inbox».
- `ArtifactPublishUseCase` — выход: заливка готовых датафреймов на приёмник.

**4. Вход: идемпотентность без записи в источник.** Источник трогаем как
read-only; дубли отсекает локальный **fetch-ledger** (ключ remote `path+size+mtime`),
по образцу `FileIngestionLedger`. Отклонено для v1: «claim»-семантика
move/delete на источнике (требует прав на запись/удаление; оставлено как опция
`after:` на будущее).

**Atomic landing в inbox.** Fetch не пишет напрямую в include-visible имя. Протокол v1:
`get` скачивает remote object в локальный temp/staging-файл (`*.part` или каталог вне
`ioc.ingestion.dirs.inbox`) → закрытие/fsync где доступно → atomic move в
`ioc.ingestion.dirs.inbox` под финальным именем → только после этого ledger mark. Поэтому
ingest-daemon никогда не видит частично скачанный файл. Коллизии финального имени
разруливаются детерминированно: если remote identity уже в ledger — skip; если identity новая,
но имя занято, локальное имя получает стабильный suffix от remote identity, не перетирая
чужой inbox-файл.

**5. Выход: публикуем сформированный Export Slice, а не сырые файлы.** Источник для
publish — готовый локальный срез экспорта
([0012](0012-streaming-dataframe-emission.md)), а **не** глоб `dataframe/*_generated.csv`:
эволюция хранилища доставку не ломает. Доставке всё равно, снимок это или инкремент.
- **Атомарная публикация среза-как-каталога** — через intent `publishAtomically`
  (решение 14): адаптер делает её транспортно-нативно (SMB/SFTP: temp→rename;
  S3: объекты+маркер), `_SUCCESS` пишется **последним** как точка commit. Контракт
  «маркер ⇔ полнота» — удалённый потребитель забирает срез только целиком.
- **Верификация доставки:** сверка checksum/`_SUCCESS` после заливки перед
  подтверждением доставки (стык с export-сагой — решение 6).
- Имена/immutability/штамп покрытия — свойства *сформированного* среза (0012); доставка
  их сохраняет (каталог `<profile>/<ts>__<run_id>/` + `manifest.json` + `_SUCCESS`, см. 0012 «Формат среза»).

**6. Триггер publish — новый срез с `_SUCCESS` / таймер; доставка — отдельная сага (F3).**
Доставка реагирует на **появление нового Export Slice с маркером `_SUCCESS`**
(`on-new-output`) либо опрашивает по интервалу, а **не** решает сама, «полон ли набор» —
это вопрос формирования ([0012](0012-streaming-dataframe-emission.md) §4a). Конфиг-форма:
`publish.trigger: on-new-output | interval | both`. **Граница с формированием (F3):**
export-сага кончается на `COMPLETED` (срез лежит локально с `_SUCCESS`); граница =
**файловая система** (`var/export/<profile>/<slice>/` + `_SUCCESS` + manifest), не общая таблица.
Доставка владеет **отдельным `publish_ledger` ключом (slice_id, target_id)** в service-DB —
per-target статус/ретраи, так fan-out (срез → N target'ов: A опубликован, B нет, C ретраит)
выражается корректно. `slice_id` берём из manifest/имени каталога (`run_id`, ref-2). Transport
`export_run` **не трогает**. Локальный срез нельзя реапить, пока в `publish_ledger` есть
недоставленные пары (стык с ретеншеном, F2). Статусы pair: `PENDING → IN_PROGRESS → SUCCEEDED`
(`FAILED` ретраится по политике; `ABANDONED` — явное operator-решение, terminal для retention).
Помимо ключа ledger хранит operational identity среза (`profile`, `slice_name`,
`manifest_sha256`) и target binding (`endpoint`, `remote_path`), чтобы restart/reconcile,
health и ручной ops не восстанавливали путь только из `slice_id`.
При старте publish scheduler сначала reconciles `готовые slices × configured targets`; missing
pair считается pending даже до INSERT, поэтому reaper не обгоняет discovery.

**Discovery готовых локальных срезов — отдельный read-only seam.** Publish use-case не
переиспользует `FileSystemSliceRetentionStore` как источник worklist: retention-store
намеренно возвращает только `SliceDescriptor` и заточен под delete-кандидаты. Для delivery
нужен `CompletedSliceCatalog`/аналогичный порт: он сканирует
`var/export/<profile>/<slice>/`, проверяет цепочку `_SUCCESS → manifest → data files` тем же
`SliceManifestCodec`/верификатором 0012 и возвращает `AvailableSlice` плюс локальный `Path`
каталога. Только verified completed slice может попасть в `publish_ledger`.

**`on-new-output` в v1 = poll/reconcile, не обязательный filesystem event.** После 0012
formation не публикует отдельное application event наружу; надёжная семантика для restart —
периодический reconcile verified slices против `publish_ledger`. Поэтому `on-new-output`
означает «на каждом тике искать новые completed slices и ledger gaps»; `interval` задаёт
частоту такого поиска. WatchService/event bus можно добавить позже как latency optimization,
но correctness не должен от него зависеть.

**Profile — неделимая publish-единица.** Target ссылается на `export-profile`, не выбирает
отдельные `artifacts`: адаптер копирует весь `<profile>/<ts>__<run_id>/` byte-for-byte, сохраняя
исходные manifest и `_SUCCESS`. Нужен другой набор файлов → отдельный profile в `ioc.export`.
Так delivery не производит производный manifest и остаётся агностичной к CSV/schema/output-mode.

**7. Объём v1 — демон-автоцикл + CLI `ioc sync`.** Авто fetch по таймеру и publish
по барьеру/таймеру в режиме `daemon`; ручная подкоманда `ioc sync` (fetch/publish
по требованию, для ops и тестов, симметрично `ioc health`). **Отклонено для v1:**
publish-после-oneshot-`extract`.

**8. Планирование/жизненный цикл — `SmartLifecycle`-бины в `bootstrap`**
(`DaemonFetchScheduler`, `DaemonPublishScheduler`) по образцу существующего
`DaemonMaintenanceScheduler`, под `@ConditionalOnProperty mode=daemon`. Сама стратегия
каденса (`interval`/`quiet-period`+max-cap) — **framework-free `CadenceSource`**
(переиспускаемый и 0012-экспортом, и под ING-7 локальную проекцию — guardrail Q2), а
Spring-обвязка остаётся в bootstrap. `TransportRegistry` (name → транспорт+коннект) —
тоже в bootstrap. Use-case остаются чистыми. (Согласует размещение с
[0012](0012-streaming-dataframe-emission.md): планировщик в bootstrap, не отдельный
`adapter-scheduler`.)

**Lifecycle ordering после 0012.** Publish scheduler запускается после export formation
scheduler и до slice-retention scheduler. Практическая фазировка:
`DaemonExportScheduler.PHASE = 100`, `DaemonPublishScheduler.PHASE = 150`,
`DaemonSliceRetentionScheduler.PHASE = 200`. В `DaemonPublishScheduler.start()` сначала
синхронно выполняется reconcile `verified completed slices × configured targets →
publish_ledger`, затем стартует периодический цикл. Это закрывает гонку, где retention мог бы
удалить новый slice до того, как delivery успело завести pending pair.

**9. Секреты — через `${ENV}`, не коммитим.** Пароли SMB — env-ссылки в
`configs/application.yml` (gitignored) или systemd env-файле из
[packaging/](../../packaging/). SMB3 + шифрование где доступно; адаптер **не
логирует** креды.

**10. Наблюдаемость — диагностика + ECS-логи (детальная таксономия).** Новая категория
`DiagnosticCategory.SYNC` (`id` `SYNC.<NAME>`, `messageKey` `sync.<kebab>`) + ECS-события;
статусы последнего fetch/publish (время, счётчики, ошибки) — в `ioc health`; `--dry-run`
для sync — в духе `extract --dry-run`. Конвенции — [diagnostics.md](../diagnostics.md) /
[logging-taxonomy.md](../logging-taxonomy.md).

**Дисциплина полноты** (правило проекта): error-ось — *замкнутая*, фиксируем полно **в
дизайне** сейчас; flow-коды, `event.action` и `ioc.*`-поля — ядро + рост, в
enum/каталог/whitelist заводятся **вместе с producer'ом** (на реализации). Здесь —
*планируемый* набор.

**Error-ось — два слоя** (закрывает Open Q1 «нейтральная таксономия ошибок»). Адаптер
транслирует исключения транспорта (smbj-типы в `core` не текут) в порт-нейтральный
`RemoteErrorKind`; use-case/bridge маппит его в диагностический код и решает ретрай по
`retryable`:

| `RemoteErrorKind` (порт) | `SYNC.<code>` | severity | disposition (реш. 15) |
|---|---|---|---|
| `UNREACHABLE` | `ENDPOINT_UNREACHABLE` | ERROR | `RETRY_LATER` (macro, next tick) |
| `AUTH_FAILED` | `AUTH_FAILED` | FATAL | `FAIL` |
| `PERMISSION_DENIED` | `PERMISSION_DENIED` | ERROR | `FAIL` |
| `NOT_FOUND` | `REMOTE_NOT_FOUND` | WARN | `FAIL` |
| `TRANSIENT` | `TRANSPORT_TRANSIENT` | WARN | **`RETRY_NOW`** (micro, сразу) |

Диспозиция (`RETRY_NOW`/`RETRY_LATER`/`FAIL`) — **в коде, не конфиг**; кормит retry-модель
(решение 15).

**Прочие коды (ядро, расширяемое):** `PUBLISH_VERIFY_FAILED` (ERROR, реш. 14),
`ENDPOINT_UNKNOWN` (FATAL — fetch/publish ссылается на несуществующий endpoint-name,
ср. CFG-2), `CREDENTIAL_MISSING` (FATAL — `${ENV}`/секрет не разрезолвен). Preflight-коды
(`ENDPOINT_PROBE_FAILED`/`PERMISSIONS_TOO_BROAD`) — резерв под OPS-3.

**`event.action` (планируемые, snake_case):** `sync_fetch_start`/`sync_fetch_complete`,
`sync_publish_start`/`sync_publish_complete` (cycle + `EventOutcome`); `remote_fetch`,
`remote_publish` (op-level). По образцу `*_start/*_complete` + `source_read`/`artifact_write`.
Diagnostic **не диктует** `event.action` — код лишь добавляет `ioc.diagnostic.*` к событию.

**Поля (ECS-first):** новых *базовых* нет. Reuse ECS — `file.path`/`file.name`/`file.size`
(удалённый объект), `server.address` (хост, опц./`DEBUG` — не светим топологию), `event.duration`,
`error.*` (через bridge). Project `ioc.*`: 🆕 **`ioc.sync.endpoint`** (логический эндпоинт —
ECS-эквивалента нет); reuse `ioc.artifact.name`/`ioc.mode`; **`ioc.run.id`** расширяем по
семантике «pipeline/fetch/publish-цикл» (корреляция цикла, без нового поля); `ioc.sync.files`
(счётчик за цикл) — опционально/по потребности. Идентичность среза/watermark/корреляция
publish↔export — namespace **0012** (`ioc.export.*`), publish их переиспускает.

**Реализация (этап sync):** `SyncDiagnosticCodes` + `DiagnosticCategory.SYNC` → регистрация
в `DiagnosticCatalogs`, регенерация [diagnostic-catalog.md](../diagnostic-catalog.md), бамп
счётчика в `DiagnosticCatalogTest` (CODE-4); `event.action`/`ioc.*` — в `EventAction`/whitelist
рядом с producer'ом.

**11. Изоляция сбоев.** Сеть/авторизация не валят демон и не портят локальные
артефакты; retry/backoff по образцу `ioc.ingestion.retry`; упавший проход
повторяется на следующем цикле; publish **не блокирует** пайплайн.

**12. Библиотеки — smbj за тонким портом + переиспользование Spring Integration.**
- **Транспорт SMB → `smbj`** (чистый SMB2/3, без SMB1, SMB3-шифрование) за нашим
  **file-ops портом** (тонкие `list/get/stat/delete` + intent `publishAtomically`,
  решение 14); типы smbj не утекают в `core` (ArchUnit).
- **Driving-сторона (поллинг/планировщик) → Spring Integration / Spring scheduling,
  которые уже в проекте** (`spring-integration-file` в `adapter-ingest`) — не новая
  зависимость; fetch-поллинг по образцу существующего inbox-poll.
- **Apache Camel — отклонён:** EIP-фреймворк-центр-тяжести (роуты вместо use-cases),
  оверкилл для тонкого среза доставки (та же логика, что отказ от Spark в 0012).
- **Apache Commons VFS — на радар, не сейчас:** единый file-API дублирует наш
  `FileTransport`-порт, а его SMB-провайдер на jcifs слабее по SMB2/3, чем smbj
  (широта ценой качества SMB). Кандидат в будущий `adapter-transport-vfs` за тем же
  портом, если мульти-протокольность когда-нибудь перевесит. Смена транспорта у нас и
  так решена архитектурой (новый `adapter-transport-*` за портом, OCP).
- **EIP-паттерны заимствуем концептуально** (Polling Consumer, Idempotent Receiver =
  fetch-ledger, atomic publication = staging+`_SUCCESS`), без принятия тяжёлого
  EIP-фреймворка — как Dataflow-модель без Spark в 0012.

**13. Жизненный цикл соединения — stateless порт (Модель A).** `FileTransport` —
**stateless**: use-case видит только тонкие независимые файловые операции
(`list`/`stat`/`get`/`delete`) и толстый write-intent `publishAtomically`, **сессию/соединение
не видит**. Raw `put`/`rename` в порт не входят: они являются транспортными примитивами
конкретного адаптера и не являются переносимым application-контрактом. Жизненный цикл соединения —
**внутренняя забота адаптера**, управляемая на уровне `bootstrap` (адаптер —
Spring-bean с lifecycle: лениво открыть, **переиспользовать в пределах цикла**, закрыть
по idle-timeout/shutdown) — **точно как Hikari-datasource** в storage-слое (application
stateless, lifecycle держит bootstrap). Соединение **reused-per-endpoint** (не per-op:
SMB-handshake на каждый файл дорог; не тяжёлый конкурентный пул: демон-цикл
однопоточный). `RemoteSession` **уходит из порта** → внутренний тип адаптера (правка
решения 2). Прецедент в репо — `CanonicalArtifactRepository` (stateless порт +
Hikari-пул внутри адаптера).
- **Парно со Шагом 5:** publish — **толстый intent** (`publishAtomically(slice)`),
  чтобы единственный атомарный multi-op-батч жил в адаптере и не требовал сессии в
  порту; fetch (`list → ledger → get×N`) — независимые операции, прозрачно переиспускают
  коннект адаптера.
- **Отклонена Модель B** (явный `RemoteSession` в порту): корректность атомарности
  сессии не требует (SMB rename работает через любую сессию), а B протекает
  «соединением» в `application` и вырождается на connectionless-S3.

**14. Атомарность публикации — толстый intent `publishAtomically`, тонкие независимые
операции.** Принцип границы: **тонко там, где операции независимы; толсто ровно там,
где есть multi-file инвариант.**
- Read/cleanup (`list`/`get`/`stat`/`delete`) — **тонкие** (независимы, атомарность не
  нужна): fetch и remote-retention.
- Write-сторона — **один intent**:
  `publishAtomically(endpoint, remotePath, localDir, commitMarkerName)` — адаптер
  зеркалит `localDir` → `remotePath`, **файлы данных первыми**, `commitMarkerName`
  (`_SUCCESS`) — **последним** как точка commit.
- **`rename` и сырой `put` в порт НЕ выносим** — это ровно примитивы, которых нет у S3
  (нет атомарного rename, нет каталогов); они спрятаны внутри intent. Каждый адаптер
  делает атомарность **транспортно-нативно**: SMB/SFTP — temp→rename; S3 — объекты,
  затем объект-маркер. Так **S3-перекос снят на уровне контракта**.

**Контракт `publishAtomically`:**
- **Инвариант:** `_SUCCESS` появляется на приёмнике ⇔ все файлы данных полностью там
  (маркер — точка линеаризации).
- **Два контекста `_SUCCESS`:** контент маркера/checksum — свойство сформированного
  среза (0012); **порядок записи на remote** (data → marker последним) — забота
  адаптера 0011 (даже если в локальном `var/export/...` маркер уже лежит).
- **Верификация — best-effort:** размеры всегда, хэш где транспорт даёт дёшево (S3 ETag;
  SMB/SFTP server-side хэша нет → размер/re-read). Полную хэш-сверку везде не обещаем
  (ср. ACL-аудит OPS-3).
- **Идемпотентность / forward-recovery:** срез immutable (timestamp-имя); падение до
  маркера → потребитель пропускает (нет `_SUCCESS`) → повтор перезаписывает только своё
  частичное состояние, маркер один раз в конце. Успех фиксируется в `publish_ledger` pair
  `SUCCEEDED`; `export_run` доставка не меняет (решение 6).
- **Crash после remote commit, до ledger commit:** если повтор видит на remote валидный
  `_SUCCESS` и manifest/checksum совпадают с локальным slice — операция считается уже
  опубликованной и pair атомарно доводится до `SUCCEEDED` без повторной заливки. Если remote
  path существует без `_SUCCESS`, адаптер вправе перезаписать/очистить только своё partial/temp
  состояние и снова записать marker последним. Если `_SUCCESS` есть, но manifest/checksum
  расходятся — это `PUBLISH_VERIFY_FAILED`; такое состояние нельзя маскировать как успех.
- **Покрыт port-TCK** (как `IngestionLedger` TCK в `core/ioc-application-tck`): один
  контракт-тест «маркер виден ⇔ данные полны; бросок ⇒ маркера нет», каждый
  `adapter-transport-*` его гоняет — страховка атомарности для SFTP/S3.

**15. Reconnect и ретраи — три слоя (политика в ядре, механизм в адаптере, оркестрация в
планировщике).** Смешиваются три разные вещи; раскладываем по местам:
- **Политика + исполнитель — тонкая база в `core`** (`application`, framework-free):
  `RetryPolicy` (`max-attempts`/`backoff`/`multiplier`/`max-backoff`/`jitter`) + крошечный
  `Retrier` (цикл попыток + backoff + предикат по disposition). Без Spring и транспорта →
  любой адаптер зовёт `retrier.execute(op)`. spring-retry (он только в `adapter-ingest`,
  SI-flow) в транспортный адаптер **не тащим** — `adapter-transport-smb` остаётся smbj-only.
- **Reconnect + micro-retry — в адаптере.** Reconnect **отдельным дизайном не нужен** —
  выпадает из решения 13 (адаптер сам лениво открывает/переиспользует/реконнектит соединение).
  Micro-retry = повторный `retrier.execute(op)` на `RETRY_NOW` (TRANSIENT; малый N, короткий
  backoff); connection-слой адаптера прозрачно переоткроет умершую сессию на повторе.
- **Macro-retry — в scheduler'е (bootstrap).** Упавший цикл повторяется на следующем тике
  (решения 8, 11); своей ручки не заводит — это `fetch.interval`/`publish.interval`. Без
  reconnect, просто переинвок use-case.

**Предусловие — идемпотентность** (уже заложена): fetch-ledger (реш. 4) + атомарный
publish/immutable срез (реш. 14) делают повтор (обоих уровней) безопасным.

**Конфиг vs код** (как Q4 в 0012): **числа — в конфиг** (`ioc.sync.retry`, ручка оператора,
`@Validated`); **классификация `RemoteErrorKind → disposition` — в коде** (корректность, не
ручка: оператор не должен сделать `AUTH_FAILED` ретраябельным). Конфиг bind'ится в value-object
`RetryPolicy` в bootstrap и передаётся внутрь (core остаётся framework-free). Дефолты =
безопасное из коробки. **Seam:** per-endpoint override ретраев — по потребности (старт с общего
`ioc.sync.retry`); backoff-на-устойчивый-сбой между macro-циклами — опционально/потом.

**НЕ делаем:** circuit breaker / Resilience4j — оверкилл для нескольких эндпоинтов.

**16. Резолв кредов — credential-blind порт, binding-time резолв, порт-резолвер отложен.**
Расширяет решение 9 (секреты через `${ENV}`). Раскладка по жизненному циклу секрета:
- **Порт credential-blind:** use-case/`FileTransport` кред **не видит** (least-privilege,
  консистентно с реш. 13). `TransportRegistry` (bootstrap) резолвит endpoint-name →
  коннект-параметры (вкл. креды); адаптер строит smbj `AuthenticationContext`. Креды
  per-endpoint.
- **Получение (v1):** env / systemd env-file (`${ENV}`); в коммиченном конфиге — только
  ссылка `${SMB_PASS}`, не плейнтекст. Vault/AWS-SM — seam.
- **Резолв — развилка по _времени_, не «откуда»:** **binding-time** (Spring property-source,
  `${ENV}`; Vault тоже может ехать через `spring-cloud-vault` тем же `${…}`-механизмом, без
  своего порта) достаточно для **статических** секретов. **connect-time** `CredentialResolver`-
  **порт** оправдан только для **динамических/ротируемых** секретов (Vault dynamic, короткие
  leases — startup-резолв не ротируется). v1 = binding-time; acquisition прячем за тонким
  **внутренним seam'ом адаптера** (`SmbCredentials`/`CredentialSource`), чтобы свап на
  runtime-резолвер был локальным. Полноценный `CredentialResolver`-порт — **когда приедут
  динамические секреты** (YAGNI, как DuckDB на радаре).
- **Применение / гигиена:** smbj `AuthenticationContext`, SMB3-шифрование (реш. 9); **не
  логировать**, `char[]` + clear где либа даёт (smbj принимает `char[]`); heap-зероизацию не
  разводим (env-sourced статика и так в окружении процесса). **Actuator-гигиена:** поле
  `password` → Spring Boot маскирует `password/secret/token/key` в `/configprops` и `/env`
  бесплатно; кред не в `toString()` бинда (расширяет реш. 9 «не логируем» → «и конфиг-объект
  не светит»).
- **Failure (стык с error-осью реш. 10):** секрет не разрезолвлен → `CREDENTIAL_MISSING`
  (FATAL, fail-fast); отвергнут → `AUTH_FAILED` (FATAL, disposition `FAIL` — не ретраим, иначе
  лочим аккаунт).

**17. Форма payload — `Path` + адаптерный стриминг и трансформации.** Порт оперирует
**локальными `Path`** (`get(endpoint, remotePath, Path localDst)`; `publishAtomically(…,
Path localDir)`) — на обеих сторонах данные и так файлы (`var/inbox` / `var/export/...`), это
идиома репо (`SourceReader`, `CsvArtifactProjection`, ECS `file.path`).
- **`Path` ≠ in-memory:** адаптер читает/пишет `Path` **потоковым копированием** (channel /
  `Files.copy`) ↔ удалённый сокет → **константная память** при любом размере.
- **Трансформации (уточнено ref-9): v1 publish — byte-preserving.** Прозрачные возможности
  протокола (SMB3 encryption/compression) сохраняют end-to-end байты → manifest-checksum среза
  держится. **At-rest** (срез хранится сжатым/зашифрованным)
  **меняют байты** → это будущая **опция формирования (0012)** (не v1: срез = `.gz`/`.enc`,
  manifest покрывает финальные байты), НЕ publish-транзит — иначе checksum ломается.
  Стриминг-хэш на чтении (`DigestInputStream`) — byte-preserving, остаётся. Так единственный
  manifest — корень целостности (ref-4). (Прежняя редакция с `ioc.sync.publish.compress` отменена.)
- **Отклонены:** потоки в сигнатуре порта (утечка lifecycle в use-case, конфликт с реш. 13/14,
  нулевой чистый прирост — адаптер делает то же внутри) и `byte[]` (раздувает heap на больших
  списках).

**18. Ретеншен/удаление — наше локальное, приёмник за получателем (симметрия с fetch).** Кто
чистит накопление по обе стороны:
- **Источник fetch — НЕ трогаем** (read-only, реш. 4); дедуп — fetch-ledger, не delete.
- **Локальные срезы** (`var/export/...`, наши) — **slice-granular retention** (F2): единица =
  каталог с `_SUCCESS`, удаляется целиком, max-age/max-count **по срезам** (не leaf-reaper);
  staging/incomplete исключены. `PublishLedgerSliceRetentionGuard` сравнивает ожидаемые target'ы
  profile с ledger: missing/`PENDING`/`IN_PROGRESS`/`FAILED` блокируют, `SUCCEEDED`/`ABANDONED`
  разрешают. При sync disabled/no-target используется standalone guard. Permanent failure держит
  срез до operator abandon или opt-in `force-after`, поэтому max-count best-effort для pinned
  slices. Переиспускаем policy/scheduler ING-1; store/guard — slice-level.
- **Приёмник publish (дефолт) — ретеншен на стороне получателя.** Мы только **пишем**, не просим
  delete на чужой шаре (зеркало fetch-read-only, least-privilege); `complete`-срезы самодостаточны
  → получатель держит последний, остальное подчищает сам.
- **Opt-in publish-side ретеншен** (`ioc.sync.publish.retention`: max-age/max-count) — для
  деплоев, где **мы владеем приёмником** и хотим самоочистку. Требует delete-гранта; механически
  готово (`FileTransport.delete`, реш. 14). Симметричный аналог отклонённой fetch-`after:`-семантики.

### Эскиз конфига

```yaml
ioc:
  sync:
    enabled: false
    retry: { max-attempts: 3, backoff: 1s, multiplier: 2.0, max-backoff: 30s, jitter: true }  # micro
    endpoints:
      - { name: intel-share, transport: smb,
          smb: { host: 10.0.0.5, share: intel, domain: CORP,
                 username: ${SMB_USER}, password: ${SMB_PASS}, encrypt: true } }
      - { name: dist-share,  transport: smb,
          smb: { host: 10.0.0.9, share: dist, username: ${SMB_USER2}, password: ${SMB_PASS2} } }
    fetch:                              # вход: remote -> var/inbox
      enabled: true
      interval: 1m
      sources:
        - { endpoint: intel-share, remote-path: "/incoming",
            include: [ "*.htm", "*.docx" ] }   # источник read-only, дедуп через ledger
    publish:                            # выход: сформированные артефакты -> remote
      enabled: true
      trigger: on-new-output            # on-new-output | interval | both
      interval: 5m                      # output-mode/окно/маркер — см. 0012
      # retention: { max-age: 30d, max-count: 10 }   # opt-in: самоочистка приёмника (нужен delete-грант), реш. 18
      targets:
        - { endpoint: dist-share, remote-path: "/reputation-lists",
            export-profile: reputation-lists }
```

## Следствия

Планируемые (кода ещё нет):

- Новый модуль `adapters/adapter-transport-smb` (smbj в parent `dependencyManagement`).
- Новые пакеты: `application/sync` (use-case), `port/in/sync`, `port/out/sync`
  (**stateless `FileTransport`**, нейтральная error-модель, `RemoteFetchLedger`,
  `PublishLedger` ключ (slice_id, target_id), `CompletedSliceCatalog` для verified локальных
  slices, `PublishLedgerSliceRetentionGuard` — F2/F3;
  `RemoteSession` — внутри адаптера, решение 13).
- Manifest читается через общий `SliceManifestCodec` из `adapter-manifest-json-jackson` (0012),
  JSON не парсится в transport; каждый `export-profile` target fail-fast валидируется против
  `ioc.export.profiles`.
- Service-схема: forward-миграция под `publish_ledger` (`slice_id`, `target_id`, `profile`,
  `slice_name`, `manifest_sha256`, `endpoint`, `remote_path`, status, attempts, timestamps,
  last_error, remote checksum/verification metadata); unique `(slice_id,target_id)` — F3.
- `bootstrap`: `DaemonFetchScheduler`, `DaemonPublishScheduler` (`SmartLifecycle`, phase между
  export formation и slice retention; sync-start reconcile перед periodic loop),
  `TransportRegistry`, ветка `ioc.sync` в `IocProperties`.
- Чистый `CadenceSource` (`interval`/`quiet-period`+max-cap) — переиспускается
  0012-экспортом и ING-7 (guardrail Q2).
- **Ретеншен** (реш. 18): локальные срезы — slice-store + delivery guard поверх policy/scheduler
  ING-1; приёмник — за получателем по умолчанию, opt-in remote retention где владеем приёмником.
- `adapter-cli-picocli`: подкоманда `ioc sync`.
- `application.yml`: блок `ioc.sync`; ArchUnit-правило «smbj не виден из `core`».
- Доки: пользовательский `docs/sync.md` (или раздел в `ingestion.md`), строка в
  индексе `docs/dev/README.md`.

## Открытые вопросы

1. ✅ **Где проходит граница транспорт-агностичности? — РЕШЕНО (фасеты, решения 13–17).**
   Порт — тонкие независимые file-ops (`list/get/stat/delete`) + толстый intent
   `publishAtomically` (реш. 14), `Path`-payload, stateless (реш. 13); сервис — оркестратор;
   образец в репо — `PatternEngine`/`SourceReader`/storage-порт. Граница — **не одна линия,
   а набор seam'ов**, все закрыты:
   - ✅ **Сессия / жизненный цикл соединения** — Модель A (stateless порт, lifecycle в
     адаптере/bootstrap), **решение 13**.
   - ✅ **Атомарность публикации** — толстый `publishAtomically` intent + тонкие
     независимые операции; `rename`/`put` убраны из порта (S3-перекос снят),
     **решение 14**.
   - ✅ **Переподключение / ретраи** — 3 слоя: тонкий `RetryPolicy`/`Retrier` в `core`,
     micro-retry+reconnect в адаптере (reconnect выпадает из реш. 13), macro = повтор
     цикла в scheduler'е; диспозиция `RETRY_NOW/LATER/FAIL`, **решение 15**.
   - ✅ **Резолв кредов** — credential-blind порт; binding-time резолв (Spring
     property-source, `${ENV}`); `CredentialResolver`-порт отложен до динамических
     секретов (порт-vs-нет решает _время_ резолва), **решение 16**.
   - ✅ **Нейтральная таксономия ошибок** — порт-нейтральный `RemoteErrorKind`
     (`unreachable/auth-failed/permission-denied/not-found/transient`) ↔ `SYNC.*`
     диагностические коды, **решение 10**; `retryable` кормит фасет reconnect.
   - ✅ **Форма payload** — `Path` (адаптер стримит внутри; трансформации compress/encrypt/
     hash — адаптерные seam'ы), **решение 17**.

> Вопросы формирования датафреймов над непрерывным потоком (что значит «полный
> набор», output-mode `complete`/`append`, каденс/триггер экспорта) вынесены в
> [0012](0012-streaming-dataframe-emission.md) — это отдельное направление, к
> которому доставка агностична.

## План реализации по срезам

Реализация делится на 9 срезов `S0`–`S8`. Каждый срез должен завершаться отдельным
коммитом, обновлением directory README для затронутых каталогов с Java-кодом и тестами
на новую бизнес-логику/инварианты. Порядок важен: сначала чистая модель и durable state,
затем use-case orchestration, потом реальный SMB adapter и runtime/productization.

### S0 — sync contracts, config model, diagnostics skeleton

**Commit:** `ARCH: introduce remote sync contracts`

Реализовать:

- `application/sync` с framework-free value objects и политиками;
- `port/in/sync` и `port/out/sync`;
- stateless `FileTransport` contract:
  - тонкие независимые операции `list`, `stat`, `get`, `delete`;
  - толстый write-intent `publishAtomically(endpoint, remotePath, localDir, commitMarkerName)`;
  - без raw `put`/`rename` и без `RemoteSession` в порту;
- `RemoteErrorKind` и code-owned disposition mapping `RETRY_NOW | RETRY_LATER | FAIL`;
- `RetryPolicy`/`Retrier` в `application`, без Spring и transport types;
- `ioc.sync` ветку в `IocProperties` с fail-fast validation:
  - duplicate endpoint/target names;
  - unknown endpoint references;
  - invalid trigger/retry intervals;
  - missing required SMB credential placeholders after binding;
- `DiagnosticCategory.SYNC`, `SyncDiagnosticCodes`, регистрацию в `DiagnosticCatalogs`;
- sync `EventAction` и `LogField` whitelist (`ioc.sync.endpoint`, counters/status fields);
- package-info/Javadoc/README для новых пакетов.

**Acceptance/tests:**

- unit tests для retry/disposition;
- config validation tests без поднятия transport/storage IO;
- diagnostic catalog test обновлён;
- observability taxonomy whitelist обновлён;
- ArchUnit guard: smbj/transport-specific types не доступны из `core`.

### S1 — service DB ledgers

**Commit:** `STORAGE: add remote sync ledgers`

Реализовать:

- service migration под `remote_fetch_ledger`;
- service migration под `publish_ledger`;
- `RemoteFetchLedger` adapter over JDBC:
  - ключ remote identity `path + size + mtime`;
  - статус fetched/skipped/failed по достаточному для retry минимуму;
- `PublishLedger` adapter over JDBC:
  - unique `(slice_id, target_id)`;
  - operational identity: `profile`, `slice_name`, `manifest_sha256`;
  - target binding: `endpoint`, `remote_path`;
  - statuses `PENDING`, `IN_PROGRESS`, `SUCCEEDED`, `FAILED`, `ABANDONED`;
  - attempts/timestamps/last_error/verification metadata;
  - CAS/expected-status transitions для concurrent scheduler/CLI safety;
- read models для health/reconcile;
- README обновление в JDBC adapter.

**Acceptance/tests:**

- migration fresh/upgrade tests;
- contract tests для idempotent insert/reconcile;
- CAS conflict не маскируется;
- `ABANDONED` terminal для retention;
- `FAILED` остаётся retryable;
- unique `(slice_id,target_id)` защищает от duplicate discovery.

### S2 — local export slice discovery

**Commit:** `EXPORT: expose verified slices for delivery`

Реализовать:

- `CompletedSliceCatalog`/аналогичный read-only порт для publish worklist;
- model completed local slice:
  - `sliceId`;
  - `profile`;
  - `sliceName`;
  - `manifestSha256`;
  - local `Path`;
  - `SliceManifest`;
- filesystem implementation over `var/export/<profile>/<slice>/`;
- verification chain reuse из 0012:
  - `_SUCCESS` exists;
  - `_SUCCESS` contains manifest hash;
  - manifest decodes through `SliceManifestCodec`;
  - every artifact file hash/size matches manifest;
  - exact directory membership rules не ослабляются;
- fail-fast validation target `export-profile` против `ioc.export.profiles`.

**Acceptance/tests:**

- completed valid slice appears in catalog;
- staging/partial/incomplete slice is ignored or reported according to contract, but not published;
- corrupt final slice surfaces as corruption and is not treated as pending work;
- symlink/unsafe path rejected;
- profile pools independent;
- `FileSystemSliceRetentionStore` не используется как publish worklist API.

### S3 — publish application saga

**Commit:** `FEATURE: publish export slices by ledger`

Реализовать:

- `ArtifactPublishUseCase`;
- reconcile `verified completed slices × configured targets → publish_ledger`;
- per-target fan-out: один slice публикуется независимо в N targets;
- pair lifecycle:
  - `PENDING → IN_PROGRESS → SUCCEEDED`;
  - `FAILED` retryable according to policy/disposition;
  - `ABANDONED` terminal/operator decision;
- idempotent forward recovery:
  - remote `_SUCCESS` уже есть и manifest/checksum совпадает → mark `SUCCEEDED`;
  - remote partial без marker → repeat/overwrite adapter-owned partial state;
  - remote marker/checksum mismatch → `SYNC.PUBLISH_VERIFY_FAILED`;
- `PublishLedgerSliceRetentionGuard`:
  - missing pair, `PENDING`, `IN_PROGRESS`, `FAILED` блокируют delete;
  - `SUCCEEDED`/`ABANDONED` разрешают delete;
  - configured targets для profile учитываются даже до materialized ledger row;
- `ArtifactPublishObserver`/logging boundary либо reuse sync observer pattern.

**Acceptance/tests:**

- fan-out с разными target statuses;
- discovery создаёт missing pairs до publish;
- failure одного target не блокирует другой;
- crash after remote commit before ledger commit recovers to `SUCCEEDED`;
- mismatch remote marker emits diagnostic and does not mark success;
- retention guard blocks missing/pending/failed pairs;
- no mutation of `export_run`.

### S4 — fetch application use-case

**Commit:** `FEATURE: fetch remote files into inbox atomically`

Реализовать:

- `RemoteFetchUseCase`;
- source include/exclude filtering;
- read-only remote source semantics: no move/delete/claim in v1;
- fetch-ledger dedup by remote identity `path + size + mtime`;
- atomic landing protocol:
  - download to temp/staging not visible to ingest include patterns;
  - close/fsync where available;
  - atomic move to `ioc.ingestion.dirs.inbox`;
  - ledger mark only after successful final move;
- deterministic local filename collision policy:
  - already fetched identity → skip;
  - new identity with occupied name → stable suffix from remote identity;
- dry-run result model shared with CLI.

**Acceptance/tests:**

- duplicate remote identity is skipped;
- failed download leaves no final inbox file and no fetched ledger mark;
- failure after temp write before move is retry-safe;
- name collision does not overwrite existing inbox file;
- include/exclude works without transport-specific path leaks;
- source remains read-only.

### S5 — SMB transport adapter

**Commit:** `ADAPTER: add SMB file transport`

Реализовать:

- новый module `adapters/adapter-transport-smb`;
- smbj dependency в parent `dependencyManagement`;
- SMB endpoint settings:
  - host/share/domain/username/password/encrypt/timeouts;
  - credentials не попадают в `toString()`/logs;
- connection/session lifecycle внутри adapter:
  - lazy connect;
  - reused-per-endpoint within cycle;
  - reconnect on transient failure;
  - close on idle/shutdown;
- `FileTransport` implementation:
  - `list`;
  - `stat`;
  - streaming `get(Path localDst)`;
  - `delete` only for opt-in remote retention;
  - `publishAtomically`;
- SMB-native atomic publish:
  - upload data files to adapter-owned temp path;
  - write marker last;
  - temp→final rename where available;
  - verify sizes/checksum best-effort;
- transport exception translation to `RemoteErrorKind`;
- adapter README/package docs.

**Acceptance/tests:**

- port TCK for `publishAtomically`: marker visible iff complete data set visible;
- throw before marker leaves no committed slice;
- remote already committed matching slice is idempotent success;
- transient SMB errors map to `TRANSIENT`;
- auth/permission/not-found map to correct neutral kinds;
- no smbj types in `core` or application public APIs.

### S6 — bootstrap wiring and daemon schedulers

**Commit:** `FEATURE: schedule remote fetch and publish`

Реализовать:

- `TransportRegistry` в bootstrap: endpoint name → transport+settings;
- Spring composition for sync use cases/adapters under `ioc.sync.enabled`;
- `DaemonFetchScheduler`;
- `DaemonPublishScheduler`;
- lifecycle ordering:
  - export formation `PHASE = 100`;
  - publish `PHASE = 150`;
  - slice retention `PHASE = 200`;
- `DaemonPublishScheduler.start()`:
  - synchronous reconcile first;
  - then periodic loop;
- trigger semantics:
  - `on-new-output` = poll/reconcile verified slices and ledger gaps;
  - `interval` = fixed cadence;
  - `both` = both conditions, still restart-safe;
- overlap guards and controlled shutdown;
- failures isolated per source/target and retried next tick.

**Acceptance/tests:**

- publish starts before retention and after export scheduler;
- start reconcile creates pending pairs before first periodic tick;
- slow run does not overlap;
- failure one source/target does not stop daemon;
- restart repeats pending/failed work;
- disabled sync creates no schedulers and standalone retention guard remains active.

### S7 — CLI `ioc sync`

**Commit:** `FEATURE: expose remote sync CLI`

Реализовать:

- `ioc sync fetch`;
- `ioc sync publish`;
- `ioc sync all` или общий режим, если это не усложнит UX;
- `--dry-run`;
- filters/options:
  - source/target/profile selection where practical;
  - fail-fast unknown endpoint/profile before heavy IO;
- lazy resolution по аналогии с `ioc export`, чтобы unrelated commands не открывали service DB/transport;
- deterministic exit codes and concise operator output;
- CLI README updates.

**Acceptance/tests:**

- unknown profile/endpoint fails before transport resolution;
- dry-run does not write inbox/remote/ledger;
- fetch command reports fetched/skipped/failed counts;
- publish command reports pending/succeeded/failed/abandoned counts;
- transport failure returns non-zero but does not corrupt local state.

### S8 — health, docs, e2e gate

**Commit:** `TEST: gate remote sync end to end`

Реализовать:

- health read model:
  - last fetch per source;
  - last publish per target/profile;
  - pending/failed publish counts;
  - endpoint status summary;
  - retention-pinned slices count;
- generated diagnostic catalog update;
- published docs:
  - `docs/sync.md`;
  - `docs/architecture.md`;
  - `docs/modularization.md`;
  - `docs/ingestion.md`;
  - `docs/diagnostics.md`/generated catalog;
  - `docs/logging.md`;
  - `docs/techdebt.md`;
  - directory README updates;
- ArchUnit final guardrails:
  - smbj only in `adapter-transport-smb`/bootstrap wiring;
  - Spring/JDBC/Jackson boundaries unchanged;
- E2E/integration coverage:
  - remote fetch → inbox → ingest;
  - canonical write → export slice → publish;
  - restart/reconcile;
  - publish duplicate/idempotent success;
  - retention guard blocks until publish terminal;
  - fresh/upgrade service migrations.

**Final gate:**

```bash
./mvnw -B -ntp -T 1C verify
```

## Группировка срезов для реализации ИИ-агентом

Практичная группировка — 4 прохода:

1. **Проход 1: S0–S2** — фундамент без реального SMB: contracts/config/diagnostics,
   service ledgers, verified local slice discovery.
2. **Проход 2: S3–S4** — application behavior: publish saga, fetch use-case,
   delivery-aware retention guard, crash/retry invariants.
3. **Проход 3: S5** — SMB adapter отдельно, потому что это внешний dependency,
   transport-specific IO, credential hygiene и отдельные boundary risks.
4. **Проход 4: S6–S8** — runtime/productization: schedulers, CLI, health, docs,
   integration/e2e и final gate. Если объём окажется слишком большим, проход 4
   делится на `S6–S7` и отдельный hardening `S8`.
