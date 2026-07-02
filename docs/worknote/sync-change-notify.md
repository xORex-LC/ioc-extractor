# Worknote: CHANGE_NOTIFY как optional push для fetch (SYNC-10 / H6)

**Статус:** design-note спайка (НЕ ADR). Фиксирует модель, границы и открытые
вопросы перед стендовым прототипом; go/no-go — по итогам стенда. После
реализации полезное переезжает в `docs/sync.md` / ADR 0013, дока удаляется.
**Ветка:** `module/platform-event/eip-base`.

**Контекст:** ADR 0013 (реш. 11/Q9) отложил истинный push как YAGNI-seam;
[sync-hardening-issues.md](sync-hardening-issues.md#sync-10--optional-push-smb2-change_notify)
закрыл проверку API (smbj 0.14, `Directory.watchAsync`) и задал целевую границу
(отдельный optional port, не `FileTransport`). Prerequisite SYNC-2 закрыт:
`SO_TIMEOUT=0` + `request-timeout` в `SmbjShareClientFactory`. Этот документ —
результат design-обсуждения H6.

**Цель:** убрать лишнюю работу приложения — не опрашивать remote-источник по
таймеру ради «ничего не изменилось», а получать сигнал об изменении и
реагировать через уже существующую event-driven модель
(`RemoteChangeBatchDetected` → `RemoteChangeFetchListener`). Push сокращает
латентность и число холостых listing'ов; корректность остаётся за
polling/reconcile.

---

## 1. Протокольная основа

`SMB2 CHANGE_NOTIFY` (MS-SMB2 §2.2.35) — подписка на изменения **каталога**:
клиент открывает directory handle, отправляет запрос с completion filter
(`FILE_NOTIFY_CHANGE_FILE_NAME` / `_LAST_WRITE` / `_SIZE`, …) и флагом
`watchTree`; сервер держит запрос pending (server-side long-poll) и отвечает
списком `FILE_NOTIFY_INFORMATION` — пары *(action, относительное имя)*:
`ACTION_ADDED | MODIFIED | REMOVED | RENAMED_OLD_NAME | RENAMED_NEW_NAME`.

Три свойства протокола определяют дизайн:

1. **Watch одноразовый.** Ответ сжигает подписку; её надо перевзводить новым
   запросом. Между ответом и перевзводом изменения теряются.
2. **Overflow объявляется явно.** При переполнении серверного буфера ответ —
   `STATUS_NOTIFY_ENUM_DIR` с пустым списком: «что-то изменилось, перечисли
   каталог сам». Протокол сам деградирует к listing.
3. **Payload беден.** Только action + имя, без size/mtime — а
   `RemoteObjectIdentity` = `path + size + modifiedAt`. По одному уведомлению
   identity не построить; после сигнала нужен `stat`/listing.

Следствие (несущий инвариант, совпадает с ADR 0013 реш. 11):
**CHANGE_NOTIFY — ускоритель латентности, не источник фактов.** Он не может
быть correctness-механизмом (окна перевзвода, overflow, разрывы); polling
backstop обязателен, push строго аддитивен.

## 2. API smbj 0.14

Публичный API достаточен, обёртки/рефлексия не нужны:

```java
Directory dir = share.openDirectory(path,
        EnumSet.of(AccessMask.FILE_LIST_DIRECTORY, AccessMask.FILE_READ_ATTRIBUTES),
        null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null);

Future<SMB2ChangeNotifyResponse> pending = dir.watchAsync(
        EnumSet.of(SMB2CompletionFilter.FILE_NOTIFY_CHANGE_FILE_NAME,
                   SMB2CompletionFilter.FILE_NOTIFY_CHANGE_LAST_WRITE,
                   SMB2CompletionFilter.FILE_NOTIFY_CHANGE_SIZE),
        false);                     // watchTree=false: list() плоский — watch тоже
// await → response.getFileNotifyInfoList() → перевзвод watchAsync(...)
```

Блокирующий `dir.watch()` не годится: он ждёт ответ под общим `withTimeout`
(= `request-timeout`, 30s), а уведомление может не приходить часами. Только
`watchAsync` + собственное ожидание.

## 3. Модель: «дверной звонок» (doorbell), не targeted stat

Потребительская сторона fetch уже event-driven: `RemoteChangeFetchListener`
реагирует на `RemoteChangeBatchDetected` мгновенно (claim in-flight → keyed
executor → fetch). Таймер (`DaemonFetchScheduler`) нужен только чтобы
**произвести** событие. CHANGE_NOTIFY встраивается ровно в эту одну точку.

**Решение:** сигнал watch'а не транслируется в пофайловые команды. Любой ответ
сервера (включая overflow и delete-уведомления) — это один и тот же «звонок»:
*запусти `RemoteSourceMonitor.detect(source)` сейчас*. Дальше работает
существующий путь: listing → include/exclude → дедуп по `RemoteFetchLedger` и
`RemoteFetchInFlightRegistry` → `RemoteChangeBatchDetected` → listener.

Почему не targeted stat (первоначальная формулировка SYNC-10):

- `detect()` — один плоский `list()` небольшого каталога, и он **уже** содержит
  матчинг, дедуп и батчинг. Targeted stat дублировал бы эту логику вторым
  путём с собственными краевыми случаями.
- Overflow и потери уведомлений в doorbell-модели не являются особыми
  случаями — пустой сигнал означает то же, что непустой.
- Идемпотентность даёт ledger: лишний звонок = один лишний дешёвый listing,
  а не лишний fetch.

**Триггер пересмотра:** каталоги с тысячами файлов, где полный listing на
каждый звонок станет дорогим, — тогда targeted stat как оптимизация поверх
того же порта (payload-hint опционален по построению, см. §4).

## 4. Целевая архитектура

### Порт (application, framework-free)

```java
/** Optional push capability: transports without push semantics simply have no implementation. */
public interface RemoteChangeSignalSource {
    /** Starts a watch for one source; the callback is a hint to re-detect, it carries no facts. */
    RemoteChangeWatch watch(RemoteFetchSource source, Runnable signal);
}

public interface RemoteChangeWatch extends AutoCloseable {
    @Override
    void close();
}
```

- **Не** метод `FileTransport`: push-семантика есть не у каждого транспорта;
  отдельный порт сохраняет `FileTransport` stateless и честным.
- Callback без payload — суть doorbell: адаптер не обязан транслировать детали,
  application не обязан им верить.
- `RemoteChangeWatch.close()` без checked exception: lifecycle-владельцу в
  bootstrap не нужен `throws Exception` на штатном stop; adapter обязан
  best-effort закрыть transport-ресурсы и залогировать/наблюдать сбой сам.

### Detection coordinator (`bootstrap`, framework-light)

Нужен отдельный `RemoteFetchDetectionCoordinator` между periodic scheduler,
watch-сигналами и `RemoteSourceMonitor`. Это не новый broker/scheduler-framework,
а маленький driving-adapter coordinator, который делает ровно одно:
**сериализует и коалесцирует requests на detect(source)**.

Ответственности:

- единая точка входа `trigger(source, reason)` для periodic tick и push doorbell;
- debounce/coalescing per source;
- single-flight: не более одного `RemoteSourceMonitor.detect(source)` одновременно;
- если сигнал пришёл пока detect уже выполняется — поставить `rerunRequested`
  и выполнить ровно один trailing detect после debounce;
- publish событий остаётся прежним: `RemoteSourceMonitor.detect()` возвращает
  `RemoteChangeBatchDetected`, coordinator передаёт их в `ControlEventPublisher`;
- ошибки detection пишутся в `SyncHealthState` так же, как сейчас делает
  `DaemonFetchScheduler`.
- `STARTUP` и `RECONNECT_RECOVERY` причины делают обычный detect сразу после
  старта/успешного восстановления watcher'а: это закрывает файлы, появившиеся
  до установки watch'а или во время downtime демона/watch-сессии.
- сбой `ControlEventPublisher.publish()` не ломает watcher/coordinator и не
  превращает push в correctness path: ошибка observe/log, следующий periodic
  detect остаётся backstop.

Граница: coordinator не знает SMBJ, не знает `CHANGE_NOTIFY` payload, не делает
fetch. Он живёт в `bootstrap/ioc-app` как lifecycle/orchestration glue и остаётся
framework-light: без Spring-аннотаций внутри, wiring только в `SyncConfig`.

`DaemonFetchScheduler` после этого перестаёт напрямую вызывать
`RemoteSourceMonitor.detect(source)` и вызывает `coordinator.trigger(source,
PERIODIC)`. Watcher вызывает тот же `trigger(source, PUSH)`. Так periodic и push
физически не могут гоняться друг с другом.

### SMB-адаптер (`adapter-transport-smb`) — `SmbChangeNotifyWatcher`

- **Выделенное долгоживущее соединение** (собственный `SMBClient`), НЕ из
  кэша `TransportRegistry`: планировщик вызывает `transports.closeIdle()`
  после каждого цикла, и `idle-timeout` (5m) убил бы соединение под живым
  watch'ем. Watch владеет своим lifecycle; конфиг соединения — тот же
  `SmbjShareClientFactory.config()` (`SO_TIMEOUT=0`, connect-timeout).
- Цикл: open directory handle → `watchAsync` → await → `signal.run()` →
  перевзвод. Overflow — тоже сигнал. `signal.run()` обязан быть быстрым:
  только поставить trigger в coordinator, не делать listing/fetch в watcher thread.
- Любая ошибка (разрыв, `STATUS_*`, `TransportException`) → закрыть
  handle/клиент → backoff-реконнект. Используем значения backoff/multiplier/
  maxBackoff/jitter из `RetryPolicy`, но **не** `maxAttempts`: watcher в daemon
  живёт до `stop()` и не должен завершаться навсегда после N ошибок. На время
  реконнекта корректность держит polling.
- Один watcher = один source (один каталог); ошибки маппит
  `SmbExceptionMapper`.
- `close()` idempotent: отменить pending `Future`, закрыть directory handle,
  share/session/client, остановить worker и дождаться bounded timeout. Если
  close не успел — WARN + оставить shutdown продолжаться; JVM не должна висеть
  из-за pending notify.
- Credentials обрабатываются так же строго, как в `SmbFileTransport`: password
  копируется в `char[]`, затирается после `AuthenticationContext`, не попадает в
  `toString()`, health и logs.

### Bootstrap

- Lifecycle-владелец: `SmartLifecycle` рядом с `DaemonFetchScheduler.PHASE`
  (watch может стартовать в той же фазе — он только ускоряет detection).
- Конфиг: `ioc.sync.fetch.sources[].change-notify.enabled: true` — **opt-in
  per source**, по умолчанию `false`. Интервал поллинга не меняется
  автоматически; оператор может поднять `fetch.interval` сам, когда watch
  подтверждён.

```yaml
ioc:
  sync:
    fetch:
      sources:
        - name: incoming-ioc
          endpoint: delivery-share
          remote-path: /incoming
          change-notify:
            enabled: true
            debounce: 3s
```

- Если `change-notify.enabled=true`, но endpoint transport не предоставляет
  `RemoteChangeSignalSource`, startup должен fail-fast с понятной ошибкой.
  Явный opt-in не должен молча превращаться в polling-only.
- Runtime SMB-сбои `openDirectory` (`ACCESS_DENIED`, `OBJECT_NAME_NOT_FOUND`,
  `PATH_NOT_FOUND`) не валят приложение после старта: watch source переходит в
  `RECONNECTING/DEGRADED`, polling остаётся активным. Если и periodic listing
  не работает — это уже существующий fetch health outcome.
- **Debounce/coalescing** (~2–5s, конфигурируемо): запись файла порождает
  пачку уведомлений (ADDED + серия MODIFIED); один detect на пачку достаточен.
- **Single-flight detect:** реализуется coordinator'ом, не watcher'ом.
- **Resource budget:** каждый active watcher держит SMB connection/session/share,
  directory handle и worker. Health показывает active watcher count; thread names
  включают source/endpoint. Если enabled sources станет много или несколько
  sources смотрят на один каталог, вводим dedupe по `WatchKey(endpoint,
  normalizedRemotePath)` с fan-out на sources.

`WatchKey(endpoint, remotePath)` нужен не для разных хранилищ: разные хранилища
обычно уже разные `endpoint` и должны иметь разные watcher'ы. Он полезен, когда
несколько `RemoteFetchSource` читают один и тот же каталог одного endpoint'а с
разными include/exclude правилами.

### Health / observability

- Состояние watch per source: `ACTIVE / RECONNECTING / DISABLED` + счётчики
  (signals, coalescedSignals, detectsTriggered, reArms, reconnects, overflows,
  lastSignalAt, lastError) в sync health read model.
- Detection metrics для решения о будущей targeted-stat оптимизации:
  `detectDurationMs`, `listedObjects`, `emittedObjects`, `suppressedByLedger`,
  `suppressedInFlight`, `coalescedSignals`.
- Потеря watch — **не fatal и не DOWN**: backstop работает, деградирует только
  латентность. WARN-лог + отдельное поле health; в `SyncOperationalStatus`
  не выше DEGRADED, и только если реконнект не восстанавливается.

## 5. Удаления и retention (`ACTION_REMOVED`)

**Решение v1:** на удаления семантически не реагируем — в doorbell-модели
`ACTION_REMOVED` это просто звонок. Чистка `remote_fetch` ledger по
delete-событиям отвергнута:

- события теряются по построению (окна перевзвода, downtime) → event-driven
  cleanup был бы неполным и всё равно требовал бы reconcile «list vs ledger»;
  это противоречит инварианту «события — подсказка, ledger — истина»;
- удаление на remote обычно ротация у поставщика; удаление записи ledger
  привело бы к повторному fetch того же файла при его возврате — безвредно
  (keep-first ниже), но это ровно та лишняя работа, которой мы избегаем.

**Watch-item (retention):** если рост `remote_fetch` ledger станет заметен —
инструмент уже есть в системе: retention **по возрасту** записей, отдельным
maintenance-джобом по образцу publish-ledger/slice retention
(`ioc.maintenance` / `PublishLedgerSliceRetentionGuard`), не по событиям.
До появления симптома не делаем.

## 6. Открытые вопросы стенда (go/no-go)

### 6.1 `watchAsync` и `request-timeout`

**Вопрос:** роняет/отменяет ли smbj pending `CHANGE_NOTIFY` по общему
`withTimeout` (`request-timeout` = 30s)? Ответ приходит асинхронно через
`STATUS_PENDING`; поведение transact-timeout можно подтвердить только живым
сервером.

**Выбранная mitigation:** обычный `request-timeout` остаётся timeout'ом для
`list/read/write/transact`. Watcher получает internal seam для отдельного
watch-timeout/lease policy. Публичный knob не добавляем до стенда.

Если pending watch переживает `2 × request-timeout`, оставляем общий `SmbConfig`
для v1. Если future падает ровно по timeout без изменений на сервере, выделенный
watch-клиент получает отдельный lease-timeout (например 30–60m). Такой timeout
трактуется как **плановый re-arm/reconnect**, а не как ошибка transport:
DEBUG/INFO + счётчик re-arm, без `DOWN`.

**Стендовый критерий:**

- idle watch без изменений живёт минимум `2 × request-timeout`;
- после idle-периода создание файла доставляет notify;
- если timeout всё же срабатывает — watcher re-arm'ится и остаётся `ACTIVE`
  либо кратко `RECONNECTING`, polling продолжает correctness path.

### 6.2 Многочасовой idle TCP / firewall / NAT

**Вопрос:** при `SO_TIMEOUT=0` локальный socket не закрывает idle read, но
firewall/NAT может выкинуть TCP state так, что клиент узнает об этом только при
следующем IO.

**Выбранная mitigation v1:** correctness держит periodic polling; для latency
предпочитаем **bounded watch session lease**, а не no-op echo. Watcher
периодически закрывает directory/share/client и открывает watch заново
(`max-watch-session-age`, internal default до стенда). Echo/no-op не вводим в
v1: он мультиплексирует служебный IO с long-poll session и усложняет lifecycle.

**Стендовый критерий:**

- restart/kill Samba во время pending watch переводит source в `RECONNECTING`,
  затем обратно в `ACTIVE`;
- `close()` не висит на pending notify;
- polling fetch продолжает работать во время `RECONNECTING`;
- если lease не помогает против silent half-open в реальной сети, только тогда
  рассматриваем TCP keepalive/echo как отдельный hardening.

### 6.3 Samba vs Windows Server

**Вопрос:** Samba и Windows Server могут отличаться деталями notify: какие action
приходят при temp-write → rename, приходит ли `LAST_WRITE` при дозаписи, как
выглядит overflow, что даёт `watchTree=false`.

**Выбранная mitigation:** payload не является фактом. Любое уведомление,
overflow, delete/rename или status с семантикой `STATUS_NOTIFY_ENUM_DIR` — один
и тот же doorbell: `trigger detect(source)`.

**Стендовая матрица:**

- create final included file;
- create temp/excluded file → rename to included final;
- append/modify existing file;
- delete file;
- rename old/new;
- burst 1k+ files для overflow;
- no-change idle дольше timeout/lease;
- stop/cancel watcher while pending.

Samba-стенд обязателен для go/no-go реализации. Windows Server — manual/staging
check перед формулировкой production-ready; до него capability документируется
как optional accelerator с server-dependent semantics.

## 6a. Митигация рисков

| Риск | Решение / митигация |
|---|---|
| `watchAsync` всё равно падает по общему `request-timeout` | Стендовый go/no-go: pending notify должен пережить минимум `2 × request-timeout` без изменений. Если не переживает — watcher получает выделенный `SmbConfig` с отдельным watch-timeout/lease policy. Ожидаемый lease-timeout считается плановым re-arm, не failure. Публичный config knob не добавляем до подтверждения; сначала internal seam в `SmbChangeNotifyWatcherFactory`. |
| Полумёртвое idle TCP за firewall/NAT не даёт ни notify, ни ошибку | Correctness держит polling. Первичная latency-mitigation — bounded watch session lease: периодически закрывать и открывать watch-сессию заново (`max-watch-session-age`), а не слать параллельные no-op операции на тот же handle. Echo/TCP keepalive — только follow-up, если lease не помогает в реальной сети. |
| Shutdown зависает на pending notify | `RemoteChangeWatch.close()` обязан отменять future, закрывать directory/share/client и ждать worker bounded timeout. Interrupt восстанавливается; после timeout — WARN и продолжение shutdown. Никаких неограниченных `await/get`. |
| Overflow приходит не как пустой список, а как SMB status/exception | Adapter treats both forms as doorbell: empty notify list, explicit `STATUS_NOTIFY_ENUM_DIR`, or mapped exception with that status all call `signal.run()` and re-arm. Стенд фиксирует фактическое поведение smbj/Samba/Windows. |
| Event storm при дозаписи большого файла | Coordinator debounce + single-flight + trailing rerun. Много notify превращаются максимум в один scheduled detect и один trailing detect после running detect. |
| Push включён для unsupported transport | Fail-fast при startup/wiring. Не должно быть тихого downgrade, потому что оператор явно включил capability. |
| Remote path/ACL не позволяют открыть watch | Runtime watch state `RECONNECTING/DEGRADED`, polling остаётся backstop. Если periodic listing тоже не работает, health деградирует через существующую fetch policy. |
| Файлы появились до старта демона или во время downtime watch'а | `STARTUP` и `RECONNECT_RECOVERY` trigger запускают обычный detect сразу после старта/успешного reconnect. |
| `ControlEventPublisher.publish()` падает при signal-triggered detect | Publish failure observe/log, но watcher/coordinator продолжают жить. Correctness остаётся за periodic detect + ledger/in-flight idempotency. |
| Watcher thread задерживает re-arm из-за listing/fetch | Watcher callback только enqueue в coordinator. Listing/fetch идут вне SMBJ watch loop. |
| Samba и Windows Server отличаются по notify semantics | Интеграционный профиль: Samba обязателен для spike, Windows Server — manual/staging check перед объявлением production-ready. До Windows-проверки capability остаётся documented as SMB server dependent. |
| Каталог становится большим, listing на каждый звонок дорогой | V1 остаётся doorbell. Метрики `detect duration`, `objects listed`, `events emitted` должны показать проблему. Только после этого targeted stat как optional hint поверх того же `RemoteChangeSignalSource`, не как новый correctness path. |
| Много enabled sources создают слишком много SMB handles/threads | V1 допускает per-source watcher для простоты, но health показывает active watcher count. При общем `(endpoint, remotePath)` sources можно объединить через `WatchKey` и fan-out. |
| Credentials протекают в logs/health/thread names | Использовать существующую SMB credential discipline: password copy в `char[]`, wipe после auth, no credentials in `toString`, logs, health, thread names. |

## 7. Инварианты («не делать»)

- Не делать CHANGE_NOTIFY correctness-механизмом: polling/reconcile остаётся
  обязательным и не отключается конфигом.
- Не расширять `FileTransport` push-семантикой.
- Не вводить generic watch/subscription framework в `platform-*`: порт
  предметный, живёт в application/adapters/bootstrap (как весь sync).
- Не транслировать payload уведомлений в команды fetch (v1); не чистить
  ledger по delete-событиям.
- Не менять смысл/маршрут `RemoteChangeBatchDetected`: push производит те же
  события тем же `RemoteSourceMonitor`.
- Не выполнять listing/fetch в watcher thread или SMBJ callback: push только
  ставит detect-trigger.
- Не завершать watcher навсегда после исчерпания `RetryPolicy.maxAttempts`;
  daemon watcher реконнектится до stop.
- Не логировать credentials и не включать их в health/thread names.

## 8. Реализационные срезы

0. **Стендовый прототип (go/no-go).** Голый `watchAsync`-цикл против Samba:
   вопросы §6 + риск-матрица §6a. Вне реактора (scratch), в репозиторий не
   коммитится; итог — апдейт этой доки с решением по watch-timeout/lease.
1. **Порт + coordinator каркас.** `RemoteChangeSignalSource`/`RemoteChangeWatch`,
   `RemoteFetchDetectionCoordinator`, coalescing/single-flight detect,
   config-binding (`change-notify.enabled/debounce`), health-поля; unit-тесты
   на fake-источнике, включая `STARTUP`, `RECONNECT_RECOVERY`, publisher failure
   и trailing rerun. Коммит: `FEATURE: add optional remote change signal port`.
2. **SMB watcher.** `SmbChangeNotifyWatcher`: выделенный клиент, быстрый
   callback в coordinator, re-arm loop, overflow/status handling,
   infinite capped backoff-reconnect, bounded shutdown, credential wiping,
   ACL/path error mapping; adapter contract test против стенда (профиль,
   в CI по умолчанию skip). Коммит:
   `FEATURE: push remote change detection over SMB CHANGE_NOTIFY`.
3. **Доки.** `docs/sync.md` (+ ADR 0013 superseded-note к реш. 11), закрытие
   SYNC-10, удаление этой worknote. Коммит: `DOCS: document CHANGE_NOTIFY push capability`.

Между срезами — модульный test gate; после среза 2 — reactor `verify`.
