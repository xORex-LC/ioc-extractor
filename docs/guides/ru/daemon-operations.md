# Эксплуатация daemon

Runbook описывает повседневную работу с установленным daemon ioc-extractor:
подачу sources, health, logs, failed files и защиту durable state.

## Управление сервисом

```bash
sudo systemctl start ioc-extractor
sudo systemctl stop ioc-extractor
sudo systemctl restart ioc-extractor
sudo systemctl status ioc-extractor --no-pager
sudo journalctl -u ioc-extractor -f
```

Application commands запускайте установленным launcher:

```bash
sudo /opt/ioc-extractor/bin/ioc --version
sudo /opt/ioc-extractor/bin/ioc health
sudo /opt/ioc-extractor/bin/ioc health --json
```

Default prefix — `/opt/ioc-extractor`; при другой установке замените его.

### Безопасный planned restart

До закрытия ING-10 выполняйте planned restart только в idle maintenance window:

1. приостановите local и remote producers, чтобы новые sources не поступали;
2. дождитесь завершения текущего source и пустых `var/inbox` и
   `var/processing`, включая временные/частичные files;
3. проверьте local health и последние diagnostics `INGEST.*`;
4. перезапустите service, подтвердите health и только затем возобновите intake.

Процедура уменьшает exposure к гонке startup recovery/poller, но не является
recovery и не делает существующую `FAILED` identity retryable. Если idle window
обеспечить нельзя, сохраните state и используйте reviewed maintenance procedure,
не удаляя ledger records.

## Подача source document

Daemon обрабатывает целые файлы, а не дописываемый byte stream:

```text
inbox → stability wait → atomic claim в processing
      → canonical DB commit → CSV projection → done
      └ post-claim terminal failure              → failed
pre-claim terminal failure → может остаться в inbox (ING-13)
```

Локальный файл копируйте с временным исключённым suffix и переименовывайте после
завершения:

```bash
sudo -u ioc cp report.docx /opt/ioc-extractor/var/inbox/report.docx.part
sudo -u ioc mv /opt/ioc-extractor/var/inbox/report.docx.part \
  /opt/ioc-extractor/var/inbox/report.docx
```

Rename явно обозначает producer completion. Прямое копирование в final name тоже
защищено stability quiet period, но producer не должен изменять файл бесконечно.

Не пишите в `processing`, `done` или `failed`. Не редактируйте files в
`dataframe/`: это projections, восстанавливаемые из canonical storage.

## Результаты обработки

Успешный source архивируется в `var/done`. Новые canonical rows получают
непереиспользуемые internal identities; повторный IOC сохраняется один раз, а
source provenance накапливается. Новый документ не сбрасывает и не заменяет
dataset. Поле `id` в immutable export является отдельным reusable slot,
стабильным только пока lifecycle активна.

При `collect-and-continue` recoverable item errors могут завершить run с
diagnostics. Валидные строки при этом записываются, поэтому logs/health следует
проверять и для source, попавшего в `done`.

После bounded retries, следующих за успешным claim, terminal source перемещается
в `var/failed`, а ledger фиксирует terminal state. При pre-claim failure source
может остаться в `var/inbox` из-за ING-13. В 0.2.0 нет поддерживаемой команды
очистки или requeue такой identity. Не переносите source для повторной подачи и
не редактируйте ledger files/SQLite tables вручную. Сохраните source там, где он
остался, вместе с logs, исправьте причину и используйте reviewed recovery
procedure; идентичный content может остаться terminally deduplicated.

## Health checks

`ioc health` запрашивает loopback actuator и возвращает таблицу:

| Exit | Значение |
|---|---|
| `0` | Overall status healthy, включая допустимый degraded state. |
| `1` | Компонент down или health response unhealthy. |
| `2` | Endpoint недоступен либо response не удалось интерпретировать. |

```bash
ioc health
ioc health --component sync
ioc health --json
curl --fail --silent http://127.0.0.1:8081/actuator/health
```

| Status | Интерпретация |
|---|---|
| `UP` | Есть успешное актуальное наблюдение. |
| `DEGRADED` | Сервис доступен, но backlog/recovery/optional integration требуют внимания. |
| `UNKNOWN` | Итоговой операции ещё не было; типично сразу после startup для optional sync. |
| `DOWN` | Required local dependency или наблюдаемая операция failed. |

Deployment health gate проверяет local storage/application readiness, а не
доступность каждого optional SMB endpoint.

## Logs и diagnostics

Daemon пишет ECS JSON в `var/logs` и systemd journal:

```bash
sudo journalctl -u ioc-extractor --since "30 minutes ago" --no-pager
sudo journalctl -u ioc-extractor -p warning --since today --no-pager
```

Stable codes `CONFIG.*`, `SOURCE.*`, `SINK.*`, `INGEST.*`, `EXPORT.*`, `SYNC.*`
задают область failure. INFO/WARN не содержат raw IOC и credentials. Per-item
TRACE включайте только на короткое контролируемое окно и затем отключайте.

При startup failure:

1. прочитайте полный список действий `CONFIG.*`;
2. по value-free override report определите выигравшие external keys;
3. сравните YAML со [справочником](configuration.md);
4. исправьте все unknown/invalid keys до restart.

## Разбор failed source

1. Остановите автоматическую resubmission со стороны producer.
2. Зафиксируйте filename, timestamp, build version и diagnostic codes.
3. Сохраните source там, где он остался (`var/failed` либо `var/inbox` для
   известного pre-claim пути ING-13), как потенциально sensitive.
4. Отделите input format/content, config, storage, permission и resource failures.
5. Проверьте canonical/projection health и факт возможного commit.
6. Исправьте причину и примените approved recovery procedure. Не удаляйте ledger
   state только ради повторного запуска.

Orphan в `processing` изолируется как failure. При нормальном restart durable
recovery повторяет незавершённый claimed work, а после DB commit доводит run
вперёд повторной projection.

## Backlog и capacity

Контролируйте:

- число и oldest age файлов в `inbox`, `processing`, `done`, `failed`;
- свободное место под installation prefix;
- размеры `var/db`, `var/export`, `dataframe`, `var/logs`;
- pending export/publish и pinned slices в health;
- повторяющиеся retry/reconcile diagnostics.

Не увеличивайте `ioc.ingestion.concurrency` для сброса backlog: 0.3.0 принимает
только `1`, любое другое значение роняет startup. Будущий concurrency потребует
отдельной модели ordering/admission и load testing claim/recovery/SQLite
contention. Сначала проверьте slow copies, parser-heavy documents, remote outage
и quiet period.

## Retention

Политики независимы:

- `ioc.export.retention` удаляет старые unpinned immutable slices;
- `ioc.maintenance.retention` обрабатывает leaf files, например `done`/`failed`.

Не включайте maintenance retention до согласования сроков investigation/backup.
Никогда не задавайте DB directory, export root, inbox или processing как leaf-file
retention target.

## Backup и recovery

Canonical business data и durable service state находятся в `var/db`. CSV
projections восстанавливаются, DB — нет. Копируйте весь DB directory вместе с
matching application/configuration release.

Upgrade, rollback и restore описаны в [deployment guide](deployment.md). Проверяйте
restore на non-production host: backup без проверенного restore не является
подтверждённой recovery point.

## Routine checklist

- Service и local health находятся в норме.
- Inbox age и processing backlog укладываются в ожидаемое окно.
- Failed files разбираются, а не молча накапливаются.
- Disk space покрывает DB growth, export, logs и backups.
- Retention не удаляет evidence для investigation.
- После deploy зафиксированы version/config.
- Optional remote sync проверяется отдельно по
  [гайду remote storage](remote-storage-sync.md).
