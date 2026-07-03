# Синхронизация с внешними хранилищами

Remote sync обеспечивает два независимых потока поверх существующих bounded contexts:

```text
remote source ──fetch──▶ var/inbox ──штатный ingest──▶ canonical SQLite
canonical SQLite ──export──▶ immutable slice ──publish──▶ remote target
```

Sync не извлекает IOC и не формирует CSV. Fetch заканчивается после атомарного
появления файла в inbox; publish принимает только проверенный completed export slice.
Транспорт скрыт за `FileTransport`; текущая реализация SMB2/3 находится в отдельном
`adapter-transport-smb`.

## Конфигурация

Функция выключена по умолчанию. Секреты передаются через environment placeholders;
публиковать реальные значения в `application.yml` нельзя.
В systemd deployment значения задаются в `etc/ioc-extractor.env` с режимом `0640`;
generic unit разрешает исходящие `AF_INET/AF_INET6` соединения для SMB (обычно TCP/445),
но не выдаёт процессу Linux capabilities и не требует CIFS mount.

```yaml
ioc:
  sync:
    enabled: true
    retry: { max-attempts: 3, backoff: 1s, multiplier: 2.0, max-backoff: 30s, jitter: true }
    endpoints:
      - name: intel-share
        transport: smb
        smb:
          host: files.example.org
          share: intel
          domain: CORP
          username: ${SMB_USER}
          password: ${SMB_PASSWORD}
          encrypt: true
          connect-timeout: 10s
          request-timeout: 30s
          idle-timeout: 5m
    fetch:
      enabled: true
      interval: 1m
      sources:
        - name: incoming-intel
          endpoint: intel-share
          remote-path: /incoming
          include: ["*.htm", "*.html", "*.docx"]
          exclude: ["*.part", ".*"]
    publish:
      enabled: true
      interval: 5m
      targets:
        - name: reputation-delivery
          endpoint: intel-share
          remote-path: /out/reputation
          export-profile: reputation-lists
```

Имена endpoint/source/target уникальны. Ссылки на endpoint и export profile
валидируются при binding; неизвестный transport отклоняется до первой операции.
SMB-соединение создаётся лениво и переиспользуется внутри endpoint; credentials не
входят в `toString()` и operational logs.

`connect-timeout` ограничивает TCP connect (DNS resolution не входит в гарантированный
wall-clock deadline), `request-timeout` ограничивает один SMB read/write/transact
request, а `idle-timeout` определяет, сколько держать неиспользуемый cached client.
Reader socket использует внутренний `SO_TIMEOUT=0`: живое соединение не закрывается
только из-за отсутствия входящих пакетов. Устаревший `read-timeout` удалён после
миграционного периода; используйте `request-timeout`.

## Fetch: remote → inbox

Источник read-only: v1 не выполняет remote move/delete/claim. Для каждого объекта
identity равен `(path, size, modifiedAt)` и хранится в `remote_fetch_ledger`.

Daemon-путь разделяет detection и execution:

1. `RemoteSourceMonitor` делает `list`, include/exclude filtering и отсекает уже
   `FETCHED` и process-local in-flight identities.
2. Найденная bounded batch публикуется как control event `RemoteChangeBatchDetected`
   без содержимого файлов.
3. Bootstrap listener превращает событие в `FetchRemoteObjectsCommand` и ставит
   work в keyed executor по endpoint. Перед admission identity атомарно claim-ится в
   in-flight registry и освобождается после success/failure/rejection; поэтому медленная
   загрузка не переэмитится на каждом monitor tick.
4. `RemoteFetchService` скачивает уже переданные identities без повторного `list`.
5. `get` пишет в скрытый `inbox/.sync-staging/*.part`.
6. После close/fsync файл атомарно перемещается в финальное inbox-имя.
7. Только после move ledger получает `FETCHED`.

CLI/manual `sync fetch` остаётся reconcile-путём: он может выполнить detection и
execution одним вызовом, сохраняя прежнее поведение команды.

Занятое имя не перезаписывается: новая identity получает стабильный suffix. Ошибка
download/move не оставляет include-visible partial file и безопасно повторяется.

## Publish: completed slice → target

Worklist строится `CompletedSliceCatalog` только из каталогов, прошедших цепочку
`_SUCCESS → manifest hash → manifest decode → artifact size/hash → exact membership`.
Staging игнорируется. Incomplete/corrupt final не превращается в publish work:
profile discovery пропускает такой каталог с `SYNC.LOCAL_SLICE_INVALID`, а
точечный lookup для уже известной ledger-pair остаётся строгим.

Для каждой пары `(slice_id, target_id)` `publish_ledger` хранит независимую сагу:

```text
PENDING ─▶ IN_PROGRESS ─▶ SUCCEEDED
                  └─────▶ FAILED ─▶ IN_PROGRESS
PENDING|IN_PROGRESS|FAILED ─▶ ABANDONED
```

`FAILED` повторяется на следующем tick; `SUCCEEDED` и `ABANDONED` terminal. Адаптер
копирует slice byte-for-byte во временный remote-каталог и делает `_SUCCESS`
последней commit-точкой. Если после crash remote marker уже существует и совпадает с
manifest hash, ledger восстанавливается вперёд в `SUCCEEDED`; mismatch даёт
`SYNC.PUBLISH_VERIFY_FAILED`. Зависший `IN_PROGRESS` старше recovery cutoff также
попадает в retryable read model: повторная попытка безопасна, потому что сначала
проверяется remote `_SUCCESS`.

Slice retention блокирует каталог, пока хотя бы для одного настроенного target нет
terminal pair, включая ещё не materialized row. Поэтому max-count остаётся best-effort
при недоставленных срезах.

## Daemon lifecycle

Порядок `SmartLifecycle`: fetch `50` → export `100` → publish `150` → slice retention
`200`. Fetch watch lifecycle (`CHANGE_NOTIFY`, если включён) стартует перед periodic
fetch scheduler и на каждом успешном открытии watch-сессии запускает обычный
detection (`WATCH_ESTABLISHED`). Publish до запуска periodic executor и на каждом periodic tick reconciles
completed slices × targets один раз на export profile, чтобы retention не обогнал discovery и потерянный
`SliceCompleted` не ждал restart. Periodic publish execution проходит через тот же
keyed executor по endpoint, что и `SliceCompleted` fast-path; scheduler ждёт completion
work-item перед idle-cleanup, поэтому fast-path и backstop не публикуют один endpoint
параллельно. Оба sync scheduler последовательны, имеют overlap guard, изолируют ошибку одного
source/target и используют следующий tick как macro retry. Shutdown завершает executor и закрывает
idle transport sessions.

### Event-driven coordination + reconcile backstop

Sync использует гибридную модель: события дают low-latency hand-off, а periodic
reconcile остаётся correctness backstop после потерянных событий, restart и crash
windows. `platform-events` задаёт только framework-free event contract и publish
port; текущая доставка — Spring bridge в `bootstrap`, а не встроенный брокер.

```text
fetch interval      ──▶ RemoteSourceMonitor ──▶ RemoteChangeBatchDetected ──▶ endpoint-keyed fetch
SMB CHANGE_NOTIFY   ──▶ doorbell debounce ─────▶ same RemoteSourceMonitor.detect(source)
watch established   ──▶ recovery detect ───────▶ same RemoteSourceMonitor.detect(source)
export complete     ──▶ SliceCompleted event ──▶ publish concrete slice
publish reconcile   ──▶ per-profile dir-listing × publish_ledger anti-join ──▶ verify only missing slices
publish interval    ──▶ publish_ledger.findRetryable ──▶ publish pending/failed pairs
```

Модель restart-safe: если процесс упал между discovery, remote commit и ledger update,
следующий tick снова сверит durable state и доведёт незавершённую работу. Поэтому отсутствие
новых файлов/срезов не является ошибкой: tick может закончиться `skipped`/already-`SUCCEEDED`
и оставить health `UP`.

### Optional SMB CHANGE_NOTIFY

Для fetch-source можно включить transport-native push:

```yaml
ioc:
  sync:
    fetch:
      sources:
        - name: incoming-ioc
          endpoint: delivery-share
          remote-path: /incoming
          include: [ "*.htm", "*.html", "*.docx" ]
          exclude: [ "*.tmp", "*.part", ".*" ]
          change-notify:
            enabled: true
            debounce: 3s
```

Это **accelerator**, а не новый источник корректности. SMB watcher держит выделенный
client/session/share/directory handle и вызывает только doorbell callback. Он не передаёт
имена файлов, не делает `stat`, не скачивает данные и не заменяет polling. Notify-ответ
с изменениями, overflow (`STATUS_NOTIFY_ENUM_DIR`) или успешное переоткрытие watch-сессии
приводит к обычному `RemoteSourceMonitor.detect(source)`, где сохраняются include/exclude,
in-flight dedup, ledger idempotency и bounded batch. Пустой успешный notify-ответ считается
spurious wake/no-op: watcher просто re-arm'ит ожидание, а correctness остаётся за polling
backstop.

Watcher бесконечно reconnect'ится с capped backoff из `ioc.sync.retry`, но игнорирует
`max-attempts`: daemon-watch является long-running capability. Pending `watchAsync`
закрывается bounded shutdown; плановый lease re-open выполняется периодически и тоже
запускает `WATCH_ESTABLISHED` recovery-detect, чтобы закрыть окно между close и новым watch.
Trailing debounce выбран как v1 trade-off: одиночный файл получает задержку `debounce`,
зато серия `ADDED/MODIFIED/RENAMED` сворачивается в один detect без сложного автомата.
После подтверждённого стабильного watch в health можно поднять `ioc.sync.fetch.interval`
до более редкого backstop-значения, чтобы сократить холостые SMB listings. Polling при этом
не выключается: он остаётся correctness-loop для потерянных notify, рестартов и ручных изменений.

Практический риск push-модели — файл может быть замечен во время записи. Базовая
защита остаётся прежней: producer convention `*.tmp`/`*.part` + atomic rename,
exclude rules, debounce и identity `(path,size,mtime)`. Если файл всё-таки был
скачан частично, дописанная версия становится новой identity и будет обнаружена
следующим detection/backstop.

## CLI

```bash
ioc sync fetch [--source NAME] [--endpoint NAME] [--dry-run]
ioc sync publish [--profile NAME] [--target NAME] [--endpoint NAME] [--dry-run]
ioc sync all [--source NAME] [--profile NAME] [--target NAME] [--endpoint NAME] [--dry-run]
```

Preflight выполняется до lazy resolution JDBC/transport graph. `sync all` проверяет
обе половины до первого IO, затем выполняет fetch → publish. `--dry-run` не меняет
inbox, remote storage или ledgers. Ненулевой failed-counter возвращает exit code `1`.

## Health и наблюдаемость

Daemon actuator contributor `sync` публикует:

- последний fetch по source и последний publish по target/profile;
- `publishPending`, `publishInProgress`, `publishFailed` из агрегатного durable ledger
  read model без загрузки исторических строк;
- `retentionPinnedSlices`;
- keyed executor state: running keys, queue depth per key, oldest age и последние
  shed/failure/dispatch-rejected сигналы;
- fetch detection state и remote change watch state: active/reconnecting/disabled,
  detection duration, re-arm count, signal count, reconnect count и last error;
- summary `UP|DEGRADED|DOWN|UNKNOWN` по endpoint.

Последние scheduler outcomes хранятся только в памяти процесса и после restart снова
имеют `NEVER_RUN`; backlog и delivery terminal state остаются durable. Транзиентный
`TRANSIENT`/`UNREACHABLE` transport outcome даёт `WARN` и `DEGRADED`; подтверждённый
успех следующей операции того же source/target возвращает `UP`. Permanent/unexpected failure
или durable `FAILED` pair переводит sync contributor в `DOWN`; отсутствие первого запуска — нет.
Восстановимый executor shed остаётся видимым в details и `WARN`, но сам по себе не переводит
contributor в `DOWN`: correctness сохраняет reconcile/backstop. Work/dispatch failure переводит
health в `DOWN`; transient executor-сигнал очищается после следующего успешного work-item того же
endpoint. Watch `RECONNECTING` отображается сразу, но переводит endpoint/contributor в
`DEGRADED` только после grace-window: push — accelerator, а polling остаётся backstop.
ECS actions: `sync_fetch_start|complete`,
`sync_publish_start|complete`, `sync_work_admission`, `sync_work_dispatch`;
пустые scheduler ticks логируются на `DEBUG`, реальная работа — на `INFO`;
поля не содержат host/share/username/password. Полный каталог ошибок —
[diagnostic-catalog.md](diagnostic-catalog.md).

## Границы v1

- только SMB transport; новый протокол добавляется отдельным adapter-модулем;
- `CHANGE_NOTIFY` доступен только для SMB endpoint и остаётся optional. Если transport
  не предоставляет `RemoteChangeSignalSource`, включённый `change-notify` fail-fast'ит
  при startup, а не молча деградирует в polling;
- fetch не удаляет remote source, publish не выполняет remote retention;
- `CHANGE_NOTIFY` delete-events не используются для чистки `remote_fetch_ledger`: исторические
  identities чистятся отдельной age-retention задачей, чтобы не связывать correctness с
  ненадёжной доставкой delete-событий;
- нет активной startup auth/write probe: endpoint status появляется после операции;
- provisioning share/ACL и ротация динамических credentials остаются внешними задачами.

Дизайн и журнал решений: [dev/0011-remote-sync.md](dev/0011-remote-sync.md).
