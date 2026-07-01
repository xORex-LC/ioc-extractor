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
      trigger: on-new-output
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

## Fetch: remote → inbox

Источник read-only: v1 не выполняет remote move/delete/claim. Для каждого объекта
identity равен `(path, size, modifiedAt)` и хранится в `remote_fetch_ledger`.

1. `list` и include/exclude filtering.
2. Повторная identity пропускается без download.
3. `get` пишет в скрытый `inbox/.sync-staging/*.part`.
4. После close/fsync файл атомарно перемещается в финальное inbox-имя.
5. Только после move ledger получает `FETCHED`.

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
`200`. Publish до запуска periodic executor и на каждом periodic tick reconciles
completed slices × targets один раз на export profile, чтобы retention не обогнал discovery и потерянный
`SliceCompleted` не ждал restart. Periodic publish execution проходит через тот же
keyed executor по endpoint, что и `SliceCompleted` fast-path; scheduler ждёт completion
work-item перед idle-cleanup, поэтому fast-path и backstop не публикуют один endpoint
параллельно. Оба sync scheduler последовательны, имеют overlap guard, изолируют ошибку одного
source/target и используют следующий tick как macro retry. Shutdown завершает executor и закрывает
idle transport sessions.

### Текущая cadence-модель и известный долг

Реализация v1 начиналась как polling/reconcile loop, но publish-контур уже
разделён на discovery и DB-driven execution:

```text
fetch interval  ──▶ remote list ──▶ ledger diff ──▶ download new identities
export complete ──▶ SliceCompleted event ──▶ publish concrete slice
publish reconcile▶ per-profile dir-listing × publish_ledger anti-join ──▶ verify only missing slices
publish interval──▶ publish_ledger.findRetryable ──▶ publish pending/failed pairs
```

Эта модель restart-safe: если процесс упал между discovery, remote commit и ledger update,
следующий tick снова сверит durable state и доведёт незавершённую работу. Поэтому отсутствие
новых файлов/срезов не является ошибкой: tick может закончиться `skipped`/already-`SUCCEEDED`
и оставить health `UP`.

Ограничение v1: `RemoteFetchService` и `ArtifactPublishService` частично совмещают две роли:

- обнаружить, есть ли работа;
- выполнить работу.

Для fetch это особенно заметно: remote `list` одновременно является проверкой изменения
источника и началом fetch-use-case. Если SMB-сервер закрыл idle share, первый `list` может
дать transient ошибку вида `DiskShare has already been closed`; следующий tick обычно
переоткрывает transport, но health кратко переходит в `DOWN`. Это не указывает на неверный
`remote-path` или credentials, если последующие ticks продолжают забирать/публиковать файлы.

Целевая модель после v1 — **state-driven sync с reconcile safety net**, а не чистые
рабочие таймеры у fetch/publish:

```text
RemoteSourceMonitor
  └─ detects remote snapshot/fingerprint change
     └─ RemoteFetchService downloads concrete new identities

PublishWorkDetector
  └─ detects completed slices × targets × publish_ledger gaps
     └─ ArtifactPublishService publishes concrete pending/failed work
```

События/детекторы дают низкую задержку и чистые границы ответственности; периодический
reconcile остаётся страховкой от потерянных событий, рестартов и crash windows. Publish при
отсутствии local delivery work должен оставаться idle и не трогать SMB; fetch всё равно
нуждается в remote snapshot/list или transport-native notification, потому что локальная БД
не знает о новых файлах на удалённой стороне.

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
- `publishPending`, `publishInProgress`, `publishFailed` из durable ledger;
- `retentionPinnedSlices`;
- summary `UP|DOWN|UNKNOWN` по endpoint.

Последние scheduler outcomes хранятся только в памяти процесса и после restart снова
имеют `NEVER_RUN`; backlog и delivery terminal state остаются durable. Failed outcome
или `FAILED` pair переводит sync contributor в `DOWN`; отсутствие первого запуска — нет.
ECS actions: `sync_fetch_start|complete`, `sync_publish_start|complete`; поля не содержат
host/share/username/password. Полный каталог ошибок — [diagnostic-catalog.md](diagnostic-catalog.md).

## Границы v1

- только SMB transport; новый протокол добавляется отдельным adapter-модулем;
- fetch не удаляет remote source, publish не выполняет remote retention;
- нет активной startup auth/write probe: endpoint status появляется после операции;
- provisioning share/ACL и ротация динамических credentials остаются внешними задачами.

Дизайн и журнал решений: [dev/0011-remote-sync.md](dev/0011-remote-sync.md).
