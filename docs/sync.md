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
Staging/incomplete игнорируются, corruption возвращается как ошибка.

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
`SYNC.PUBLISH_VERIFY_FAILED`.

Slice retention блокирует каталог, пока хотя бы для одного настроенного target нет
terminal pair, включая ещё не materialized row. Поэтому max-count остаётся best-effort
при недоставленных срезах.

## Daemon lifecycle

Порядок `SmartLifecycle`: fetch `50` → export `100` → publish `150` → slice retention
`200`. Publish до запуска periodic executor синхронно reconciles completed slices ×
targets, чтобы retention не обогнал discovery. Оба sync scheduler последовательны,
имеют overlap guard, изолируют ошибку одного source/target и используют следующий tick
как macro retry. Shutdown завершает executor и закрывает idle transport sessions.

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
