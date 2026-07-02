# Worknote: открытые проблемы sync/event-coordination (после 0013 S0–S8)

**Статус:** временная issues-дока (НЕ ADR). Собрана из эксплуатации на хосте
(лог `app-log`, стенд 127.0.0.1/test-share) и ревью-циклов реализации 0013.
**Ветка:** `module/platform-event/eip-base`. **Формат:** живой список — по мере
закрытия проблема помечается ✅ и переносится в раздел «Закрыто»; когда список
пуст, дока удаляется (полезное — в `docs/sync.md`/ADR 0013/`techdebt.md`).

**Контекст:** базовое ядро 0013 (S0–S8) реализовано и отревьюировано; findings
ревью S0–S8 (executor-телеметрия, permanent-DOWN health, re-emit флуд, DB-driven
reconcile, MDC-проброс и т.д.) — **закрыты** и здесь не повторяются. Ниже —
только то, что осталось открытым по итогам последних обсуждений.

---

## Сводка

| ID | Проблема | Severity | Статус |
|---|---|---|---|
| ~~SYNC-1~~ | ~~Execution outcome смешан с durable totals; no-op тики шумят~~ | High | ✅ закрыт |
| SYNC-2 | SMB timeout policy исправлена в коде; остаётся подтверждение на SMB-стенде без idle churn | High | код закрыт / стенд |
| ~~SYNC-3~~ | ~~Транзиентные SMB-ошибки не имели WARN/DEGRADED-классификации~~ | Medium | ✅ закрыт |
| SYNC-4 | `Connection` INFO приглушён; teardown WARN/ERROR категории требуют повторного стендового лога | Medium | стенд |
| ~~SYNC-5~~ | ~~Мёртвый config-knob `sync.publish.trigger`~~ | Low | ✅ закрыт |
| ~~SYNC-7~~ | ~~Индекс `(profile, slice_name)` под `findBySliceName`~~ | Low | ✅ закрыт |
| ~~SYNC-8~~ | ~~Двойная эмиссия `LOCAL_SLICE_INVALID`~~ | Low | ✅ закрыт |
| SYNC-9 | Watch-item: leak claim'а in-flight-реестра при abandon работы (сейчас недостижим) | Note | открыт |
| SYNC-10 | CHANGE_NOTIFY поддержан smbj 0.14; design-note готова ([sync-change-notify.md](sync-change-notify.md)), дальше стендовый прототип (go/no-go) | Design | design готов / стенд |
| ~~SYNC-11~~ | ~~Actuator health материализовал весь `publish_ledger` через `findAll`~~ | Medium | ✅ закрыт |
| ~~SYNC-6~~ | ~~Гонка periodic↔fast-path publish~~ | — | ✅ закрыт |

---

## SYNC-1 — Лог-altitude: no-op тики шумят, publish-счётчики врут

**Симптом (хост):** каждые `publish.interval` в лог на INFO падает пара
`sync_publish_start` / `sync_publish_complete: pending=0, succeeded=3, failed=0`,
даже когда тик не сделал **ничего**. `succeeded=3` — это НЕ «отправлено 3 сейчас»,
а стоячий total из `countByStatus` (все SUCCEEDED-пары за всё время). Fetch-часть
после S7-фиксов честнее: detection пишет `detected=N`, пустой тик записывает в
health 0/0/0 — но пара start/complete всё равно уходит на INFO каждый тик.
Именно это создаёт впечатление «работают вхолостую», хотя publish на холостом
тике вообще не ходит на SMB, а fetch делает один дешёвый detection-`list`.

**Корень:**
- `ArtifactPublishService.countKnownNonRetryableRecords` подмешивает
  **стоячие** totals (`counts.succeeded()` и т.п.) в результат тика
  ([ArtifactPublishService.java:~156-167]) — счётчики «сделано в этот тик» и
  «состояние ledger» слиты в один `ArtifactPublishResult`;
- `DaemonPublishScheduler` / `DaemonFetchScheduler` пишут start/complete на INFO
  **безусловно**, независимо от того, была ли работа.

**Фикс:**
1. Не менять молча смысл существующих полей `ArtifactPublishResult`, а развести
   два явных read model:
   - `PublishExecutionResult` — attempted/succeeded/failed/recovered **этого** запуска;
   - `PublishLedgerSummary` — standing pending/inProgress/succeeded/failed/abandoned.
   Аналогично не смешивать fetch detection, execution и durable ledger state.
2. Планировщики: INFO только если `actionedThisTick > 0 || failed > 0`, иначе
   DEBUG; `*_start`-строку убрать или в DEBUG (достаточно одной complete-строки
   на реальную работу). Это и есть недоделанный слой S8 «INFO→DEBUG на рутинных
   тиках» (ADR 0013, реш. 10: «INFO только на реальные факты/сбои»).

**Объём:** средний: затрагивается контракт application-порта, CLI/rendering,
health read model, два планировщика и тесты на лог-altitude. Это лучше точечного
вычитания standing totals в bootstrap: смысл результата остаётся явным для всех
потребителей.

## SYNC-2 — SMB-таймауты перепутаны (корень reconnect-churn)

**Симптом (хост):** соединение умирает через ~10 секунд после использования,
несмотря на `idle-timeout: 10m`: `PacketReader error: Read timed out` →
`Logging off session` → шумный teardown → на следующем тике reconnect. Так
каждый тик.

**Корень — мис-проводка в [SmbjShareClientFactory.java:19-20]:**
```java
.withSoTimeout(settings.connectTimeout()…)  // SO_TIMEOUT сокета = connect-timeout = 10s (!)
.withTimeout(settings.readTimeout()…)       // операционный таймаут = 30s (ок)
```
Проверено по jar smbj 0.14 (`SmbConfig$Builder`):
- `withSoTimeout` — SO_TIMEOUT **сокета**: постоянный поток `PacketReader` висит
  на блокирующем `socket.read()`; при простое дольше SO_TIMEOUT чтение бросает
  `SocketTimeoutException`, и smbj **сносит соединение**. Наш `connect-timeout`
  прокинут именно сюда → idle-коннект реапится через 10s; наш `idle-timeout`
  (`SmbFileTransport.closeIdle`) до дела не доходит.
- `withReadTimeout`/`withWriteTimeout`/`withTransactTimeout`/`withTimeout` —
  таймауты **одного SMB request/transaction**. Это не общий deadline всей
  высокоуровневой операции `list/get/put`: большой transfer может длиться дольше,
  пока отдельные SMB-запросы завершаются вовремя.
- **`withConnectTimeout` в smbj НЕТ**: установка соединения идёт через
  `socketFactory.createSocket(host, port)` с OS-default (минуты) — то есть
  `connect-timeout` в текущем виде НЕ ограничивает установку вообще; недоступная
  шара подвешивает тик на минуты.

**Целевая модель (согласована):** три честные ручки, каждая = своё имя:
```yaml
smb:
  connect-timeout:   10s  # ждать УСТАНОВКИ соединения; не успели → fail → retry (наш ConnectTimeoutSocketFactory)
  request-timeout:   30s  # ждать один SMB read/write/transact request (withTimeout)
  idle-timeout:      5m   # держать тёплый неиспользуемый коннект перед закрытием (наш closeIdle)
  # SO_TIMEOUT reader'а = 0 (зашито, НЕ ручка): idle-коннект не реапится;
  # мёртвый коннект ловится request-timeout'ом при следующем SMB request
```

**Фикс:**
1. `withSoTimeout(0)` — снять reaper (минимальный bugfix, чинит churn сам по себе);
2. `read-timeout` → `request-timeout` (переименование в `SmbEndpointSettings`,
   `IocProperties`, yml, smb-example, packaging-template, docs/sync.md). Deprecated alias
   `read-timeout` больше не принимается; binding оставляет fail-fast проверку, чтобы старый
   внешний YAML не был молча проигнорирован.
3. настоящий TCP `connect-timeout`: кастомный `SocketFactory` (несоединённый
   сокет + `socket.connect(addr, connectTimeout)`) →
   `withSocketFactory(...)`;
4. smb-тесты + прогон на стенде (лог должен показать один connect и тишину).

`connect-timeout` ограничивает TCP dial, но не обещает жёсткий wall-clock deadline
для DNS resolution. Это обычная семантика connect timeout, её нужно явно указать,
а не строить внутри адаптера отдельный DNS executor без подтверждённой потребности.

**Бонус:** живое персистентное соединение — предпосылка для SYNC-10
(CHANGE_NOTIFY-watch поверх соединения невозможен, пока reaper убивает его за 10s).

## SYNC-3 — Транзиентные SMB-ошибки = ERROR без классификации

**Симптом (хост):** разовый обрыв сессии даёт
`ERROR scheduled remote fetch source failed … DiskShare has already been closed`,
хотя это транзиент — следующий тик молча переподключается и работает.

**Корень:** `DaemonFetchScheduler.attempt` (и publish-аналог) ловят
`RuntimeException` целиком и пишут `LogEvents.error` без различения
`RemoteTransportException.kind()`. Дисциплина классификации уже есть в 0011
(реш. 10: `TRANSIENT`/`UNREACHABLE` → retryable), но на лог/health-уровень
планировщиков не доведена. Это остаток OPS-4.

**Фикс (подтверждён только лог-уровень):** не размножать одинаковые `if` в двух
планировщиках, а завести небольшой bootstrap-level `SyncOperationalOutcomePolicy`:
`kind() ∈ {TRANSIENT, UNREACHABLE}` → WARN/DEGRADED;
`AUTH_FAILED`/`PERMISSION_DENIED`/ошибка конфигурации → ERROR/DOWN;
unexpected exception → ERROR/DOWN. `LogLevel` не переносить в application core:
core владеет стабильным `RemoteErrorKind`, bootstrap — его операционным представлением.

**Health — НЕ «прибит навсегда» (уточнение по ревью):** следующий успешный
результат заменяет snapshot (`SyncHealthState.recordFetch`,
[SyncHealthState.java:~31]) — самовосстановление уже работает. Итоговое решение:
`latest confirmed outcome wins`, то есть успешная операция того же source/target
или успешный keyed work-item сразу снимает runtime `DEGRADED`. На flapping-endpoint
health может мигать `WARN ↔ UP`; sticky-until-N-successes/hysteresis не вводим,
пока нет реального операторского сигнала, что простая модель даёт шум.

## SYNC-4 — Библиотечный шум smbj в logback

**Симптом:** `com.hierynomus.*` пишет свой teardown как
`ERROR Caught exception while closing TreeConnect`, `WARN Exception while
closing session`, `INFO Closed connection …` — внутренности библиотеки в нашем
ECS-логе на высоких уровнях.

**Корень:** уровень логгеров `com.hierynomus` не пришпилен ни в
`application.yml` (`logging.level`), ни в logback-конфигах (проверено grep'ом).

**Фикс:** сначала закрыть SYNC-2 и повторно снять реальный лог. Только после этого
прижать конкретные noisy logger categories. `logging.level.com.hierynomus: WARN`
уберёт INFO/DEBUG, но не шумные WARN/ERROR; глобальный `ERROR` всё равно оставит
ERROR и может скрыть полезный transport signal. Blanket `OFF` не применять.

## SYNC-5 — Мёртвый knob `sync.publish.trigger`

**Симптом/корень:** `trigger: on-new-output|interval|both` валидируется в
`IocProperties.Publish` (~:279-286), но **никем не читается** (проверено grep):
listener и scheduler подключены безусловно. Фактическое поведение всегда
«событие + reconcile-backstop» — единственный корректный режим. Остальные
значения — футганы (`on-new-output` снял бы backstop у non-durable событий).

**Фикс:** удалить knob целиком: `application.yml:~277`, smb-example (~:362),
packaging-template, поле+валидация в `IocProperties.Publish`, упоминания в
`docs/sync.md`. В ADR 0011 не переписывать историю задним числом: добавить
короткую superseded-ссылку на обязательную модель event fast-path + reconcile
backstop из ADR 0013.
Опционально: relax дефолт `publish.interval` 5m → 10–15m (reconcile теперь
редкий safety-net, реш. 7 ADR 0013).

## SYNC-7 — Индекс `(profile, slice_name)` для `findBySliceName`

Periodic-reconcile зовёт `findBySliceName(profile, sliceName)` на каждый срез
профиля. **Уточнение по ревью:** это не полный table scan — существующий индекс
`(profile, status)` даёт SQLite сужение по `profile`; внутри профиля — скан по
`slice_name`. Композитный `(profile, slice_name)` всё равно полезен при росте
истории. **Фикс:** forward-миграция service-схемы с индексом
`(profile, slice_name)` (можно объединить с SYNC-11-миграцией, если понадобится).

## SYNC-8 — Ownership диагностики `LOCAL_SLICE_INVALID` (переформулировано)

**Прежняя формулировка была неверна.** `FileSystemCompletedSliceCatalog.find()`
УЖЕ эмитит `LOCAL_SLICE_INVALID` **перед** тем как бросить
([FileSystemCompletedSliceCatalog.java:~123]) — discovery-catch в
`ArtifactPublishService.findSliceForDiscovery` глотает исключение и только
инкрементит счётчик, диагностика при этом не теряется. Реальная проблема —
**двойная эмиссия**: retryable-путь (`findSlice`) на том же исключении может
эмитить `LOCAL_SLICE_INVALID` **второй раз** (адаптер эмитит при верификации,
сервис — в своём catch). Нужен ответ на вопрос **ownership**: кто владеет
эмиссией диагностики — адаптер (в момент обнаружения, с локальным контекстом
файла) или сервис (с контекстом пары slice×target)?

**Рекомендация:** verification/corruption принадлежит filesystem adapter — это
место обнаружения и источник физического контекста. Application service не должен
повторять emit на пойманном исключении; он добавляет диагностику только для своих
семантических случаев: slice отсутствует либо больше не соответствует ledger binding.
НЕ добавлять ещё один emit в discovery.

## SYNC-9 — Watch-item: leak claim'а in-flight при abandon (сейчас недостижим)

Если принятая работа abandon'ится (dispatch-rejection в середине дренажа
ключа), `finally release` не выполнится → identity зависнет в
`RemoteFetchInFlightRegistry` до рестарта. **Сейчас недостижимо:** fetch-пул =
`Executors.newFixedThreadPool` (unbounded-очередь) → `RejectedExecutionException`
только на shutdown, где process-local реестр умирает вместе с процессом.
**Триггер пересмотра:** если пул станет bounded/AbortPolicy — добавить TTL на
записи реестра (belt-and-suspenders). Пока — только этот note + комментарий в
коде по вкусу.

## SYNC-10 — Optional push: SMB2 CHANGE_NOTIFY

Потребительская сторона fetch уже полностью event-driven
(`RemoteChangeFetchListener` + `FetchRemoteObjectsCommand` реагируют мгновенно,
без таймера); опрос нужен только чтобы **произвести** событие. Истинный push =
CHANGE_NOTIFY: watch в `adapter-transport-smb` → notification → targeted `stat`
изменённого файла (notification не несёт size/mtime для identity) → тот же
`RemoteChangeBatchDetected`. Backstop-опрос остаётся (watch срывается,
server-side overflow) — push строго аддитивен (ADR 0013, реш. 11/Q9).

**Проверка закрыта положительно:** smbj 0.14 предоставляет публичный
`Directory.watchAsync(Set<SMB2CompletionFilter>, boolean)` и
`SMB2ChangeNotifyResponse#getFileNotifyInfoList()`. Ответ содержит action/path,
но не полную `RemoteObjectIdentity`, поэтому targeted `stat` после notification
остаётся необходимым.

**Целевая граница:** не добавлять watch в общий `FileTransport` — не каждый
transport имеет push-семантику. Завести отдельный optional port
`RemoteChangeSignalSource`; SMB adapter реализует его, bootstrap владеет lifecycle,
reconnect/cancel/re-arm, application делает targeted stat и публикует существующий
`RemoteChangeBatchDetected`. Watch одноразовый и должен перевзводиться после каждого
ответа. Overflow, разрыв watch и рестарт восстанавливаются polling-backstop.

Зависимость: сначала SYNC-2 — долгоживущий watch бессмысленен, пока SO_TIMEOUT
убивает reader через 10 секунд. Реализация CHANGE_NOTIFY — отдельный design-spike,
не часть timeout bugfix.

**Design-note готова:** [sync-change-notify.md](sync-change-notify.md). Ключевое
уточнение относительно формулировки выше: вместо targeted `stat` принята
**doorbell-модель** — любой ответ watch'а (включая overflow и delete) только
триггерит `RemoteSourceMonitor.detect(source)`, который переиспользует
существующий listing/матчинг/дедуп и публикует тот же `RemoteChangeBatchDetected`.
Targeted stat остаётся отложенной оптимизацией для больших каталогов. Там же:
решение не чистить `remote_fetch` ledger по delete-событиям (retention по
возрасту как watch-item) и go/no-go вопросы стенда (pending vs `request-timeout`,
keepalive, Samba/Windows).

## SYNC-11 — Health грузит весь исторический ledger на каждый запрос

**Симптом (найден на ревью доки):** `SyncHealthIndicator.health()` делает
`ledger.findAll()` и фильтрует/считает в памяти
([SyncHealthIndicator.java:~69-77]) — на **каждый** health-запрос (actuator-поллинг,
`ioc health`) материализуется вся история `publish_ledger`, включая давно
SUCCEEDED-пары. Ledger append-only по успешным доставкам → стоимость health
растёт линейно со временем жизни системы. Это более заметный performance-риск,
чем SYNC-7.

**Фикс:** расширить `PublishLedger` узким read model `PublishLedgerSummary`, а не
тащить SQL-форму в health adapter:
- агрегатные pending/inProgress/succeeded/failed/abandoned с фильтром configured targets;
- endpoint-status отдельным агрегатным/не-терминальным read model;
- process-local `SyncHealthState` хранит только last-attempt metadata, но не
  подменяет durable standing state.
`findAll()` оставить только для ops-tooling, где полная выборка осознанна.

---

## Целевая модель hardening

Проблемы выше закрываются не десятью независимыми `if`/config-патчами, а четырьмя
согласованными частями. Нового generic framework в `platform-*` для этого не нужно:
контракты остаются предметными и живут в sync application/adapters/bootstrap.

### 1. Transport policy

`adapter-transport-smb` владеет честным соответствием operator config технической
семантике: TCP connect timeout, SMB request timeout, idle cached-client timeout и
внутренний `SO_TIMEOUT=0`. Adapter contract tests проверяют wiring и reconnect;
стенд подтверждает отсутствие idle churn. Закрывает SYNC-2 и создаёт prerequisite
для SYNC-10.

### 2. Execution outcome vs durable state

Application sync разделяет `ArtifactPublishExecutionResult`/fetch execution result и
durable `ArtifactPublishResult`/`PublishLedgerHealthSummary`. Первый отвечает на вопрос «что сделал этот запуск», второй —
«в каком состоянии система сейчас». Scheduler/CLI/health больше не интерпретируют
одни и те же поля по-разному. Закрывает SYNC-1 и даёт основу SYNC-11.

### 3. Operational outcome policy

`RemoteErrorKind` и retry disposition остаются framework-free application-контрактом.
Bootstrap policy единообразно переводит outcome в log altitude и health status:

```text
TRANSIENT / UNREACHABLE -> WARN + DEGRADED до подтверждённого успеха
AUTH / PERMISSION       -> ERROR + DOWN
unexpected failure      -> ERROR + DOWN
no-op cycle             -> DEBUG
real successful work    -> INFO
```

Нужно отдельно определить HTTP/readiness mapping для `DEGRADED`: восстановимая
перегрузка или краткий сетевой сбой не должны автоматически выключать весь процесс
из обслуживания. Закрывает SYNC-3/4 и эксплуатационную часть SYNC-1.

### 4. Durable coordination + optional acceleration

Durable ledger/reconcile остаются correctness source. Keyed executor остаётся единым
single-flight для fast-path/backstop; строгий ledger CAS не ослабляется. Мёртвый
`publish.trigger` удаляется. CHANGE_NOTIFY добавляется только как optional latency
accelerator через отдельный port, polling остаётся обязательным backstop. Закрывает
SYNC-5/7/8 и задаёт границу SYNC-10; SYNC-9 остаётся отложенным guardrail.

### Не делать

- не вводить общий `platform-sync-outcome`/универсальный message framework;
- не ослаблять `JdbcPublishLedger.compatible()` после закрытия SYNC-6;
- не прятать все `com.hierynomus` logs до исправления transport root cause;
- не делать CHANGE_NOTIFY единственным correctness-механизмом;
- не смешивать ephemeral health snapshot с durable ledger summary.

## Реализационные срезы и точки коммитов

1. **H1 — transport timeout policy (SYNC-2).** `SO_TIMEOUT=0`,
   `ConnectTimeoutSocketFactory`, `request-timeout` + migration alias, adapter/config
   tests, стенд. Коммит: `FIX: correct SMB timeout policy`.
2. **H2 — execution outcome model (SYNC-1).** Разделить per-run outcome и ledger
   summary, адаптировать CLI/schedulers/tests. Коммит:
   `REFACTOR: separate sync execution outcome from ledger state`.
3. **H3 — operational policy (SYNC-3/4).** Общая bootstrap-классификация,
   DEBUG/INFO/WARN/ERROR altitude, health recovery semantics; после стенда — точечные
   smbj logger levels. Коммит: `OPS: classify sync degradation and quiet no-op cycles`.
4. **H4 — durable read models (SYNC-7/11).** Агрегатный health port/query,
   `(profile, slice_name)` forward migration, без `findAll()` в actuator.
   Коммит: `PERF: aggregate sync health from ledger`.
5. **H5 — cleanup (SYNC-5/8).** Удалить trigger, добавить ADR superseded note,
   закрепить ownership `LOCAL_SLICE_INVALID`, убрать дубль. Коммит:
   `CLEANUP: remove obsolete sync configuration and diagnostics duplication`.
6. **H6 — CHANGE_NOTIFY spike (SYNC-10).** Отдельное проектирование optional port,
   lifecycle/re-arm/overflow/reconnect и тестовый SMB-стенд. Не объединять с H1.

Между срезами — полный модульный test gate; после H5 — reactor `verify`. H6 имеет
отдельный go/no-go после стендового прототипа и не блокирует закрытие hardening.

## Закрыто (по мере работы переносить сюда)

- ✅ **SYNC-1:** `ArtifactPublishExecutionResult` отделён от reconcile/ledger state;
  no-op start/complete переведены на DEBUG (`0d473a1`, `8b67668`).
- ✅ **SYNC-3:** bootstrap policy переводит `TRANSIENT|UNREACHABLE` в
  `WARN + DEGRADED`, permanent/unexpected — в `ERROR + DOWN`; следующий успешный
  outcome подтверждает восстановление (`8b67668`). Recovery-семантика осознанно
  instant-clear (`latest confirmed outcome wins`), без sticky hysteresis.
- ✅ **SYNC-5:** `publish.trigger` удалён; обязательная модель fast-path + periodic
  reconcile отмечена как superseding contract в ADR 0011.
- ✅ **SYNC-7/SYNC-11:** service schema v7 добавляет индекс `(profile,slice_name)`,
  actuator использует агрегат `GROUP BY endpoint,status` и не вызывает `findAll()` (`5d16431`).
- ✅ **SYNC-8:** corruption/verification diagnostic принадлежит filesystem catalog;
  application оставляет только missing/binding diagnostics. Дубликат закрыт тестом.
- ⚠ **SYNC-2/SYNC-4:** код timeout policy и точечный `Connection: WARN` реализованы
  (`dfebf27`, `8b67668`), но итоговые teardown-категории можно закрыть только после
  повторного прогона на реальном SMB endpoint.

- ✅ **SYNC-6 — гонка periodic↔fast-path publish.** Закрыт **холистическим
  вариантом (1)**: periodic-`attempt` теперь сабмитит работу в **тот же
  keyed-executor** по endpoint (с latch-ожиданием результата; rejection →
  `shed to next reconcile cycle`, WARN) —
  [DaemonPublishScheduler.java:~110]. Сквозной single-flight: fast-path и
  backstop физически сериализованы, overlap невозможен. Есть прямой
  concurrency-тест ([DaemonPublishSchedulerTest.java:~146]).
  **Следствие:** смягчать `JdbcPublishLedger.compatible()` (прежний вариант 2)
  теперь НЕ нужно и НЕ следует — это ослабило бы CAS-контроль без существующей
  гонки.
