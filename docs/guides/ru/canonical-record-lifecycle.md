# Жизненный цикл canonical-записей

Этот гайд объясняет оператору, как включить и наблюдать фиксированный срок
актуальности записей. Механизм управляет тем, какие canonical IOC-записи активны
внутри ioc-extractor. Он не доставляет команды удаления в DNS/firewall и не
считает отсутствие записи в одном source document её отзывом.

## Ожидаемое поведение

Каждое успешное canonical confirmation назначает записи настроенный срок жизни.
Повторное принятое наблюдение продлевает активный lifecycle. Если до deadline не
поступило нового подтверждения, запись удаляется из active membership в SQLite,
копируется в bounded history и исчезает из mutable CSV-проекций. Наблюдение
после expiry создаёт новый lifecycle и новый service-owned public ID.

Active set может быть пустым — это нормальное состояние. Каждый source document
считается неполной пачкой наблюдений: отсутствующие в новом документе записи не
отзываются досрочно, а доживают оставшийся TTL.

Expiry обновляет mutable-проекции `dataframe/*_generated.csv`, включая файлы
только с заголовком при пустом active set. При этом он намеренно **не** создаёт
immutable export slice и не увеличивает insert-driven export revision. Когда
следующий принятый source добавит новые строки, обычный export сформирует срез,
содержащий только записи, активные на общем snapshot time.

## Новая установка

Production packaging template включает режим `fixed` с TTL `12h`. History и
complete confirmation receipts хранятся `30d`. В чистой БД legacy rows нет,
поэтому безопасный guard `existing-records: reject` допускает activation без
destructive migration.

Встроенный classpath default остаётся `disabled`. Поэтому запуск jar без
production template не включает TTL неожиданно.

## Обновление существующей установки

Не совмещайте rollout нового binary и destructive legacy expiry в одном restart.

1. Создайте единый recovery point из active immutable application, полной
   operator configuration, `ioc-dataframe.db` и `ioc-service.db`. Включите SQLite
   side files либо используйте SQLite-consistent backup при остановленном сервисе.
2. Разверните новый binary, сохранив в существующей конфигурации
   `mode: disabled` и `existing-records: reject`. Installer не перезаписывает
   operator file, а помещает новый fresh-install template в
   `application.yml.new` для review.
3. Выполните первый startup и дождитесь local health `UP`. Compatibility start
   применит additive schema migrations, но сохранит все legacy rows активными.
4. Остановите intake и optional synchronization. Установите `mode: fixed`,
   положительный `fixed-ttl` и `existing-records: expire`.
5. Перезапустите сервис. Admission архивирует и удалит все pre-activation rows
   до открытия intake, stateful oneshot/export и lifecycle scheduler. Пустой
   active storage и projections только с заголовком являются корректным итогом.
6. Дождитесь health `UP`, проверьте aggregate lifecycle counts и возобновите
   producers. Новые принятые наблюдения заново наполнят active set.

Activation сохраняется в БД и необратима для этой dataframe database. После неё
простая смена configuration обратно на `disabled` приводит к startup failure.

## Health и системные часы

Сервис использует системные UTC-часы хоста через изолированную lifecycle clock
boundary. Поддерживайте NTP/time synchronization и не откатывайте часы назад для
продления актуальности IOC.

```bash
ioc health --component lifecycle
ioc health --json
curl --fail --silent \
  http://127.0.0.1:8081/actuator/health/lifecycle
```

Lifecycle component показывает только агрегаты и не возвращает IOC или source
identifiers.

| Поле | Значение |
|---|---|
| `activation` | Сохранённое состояние `DISABLED_COMPATIBLE`, `ACTIVATING` или `ACTIVE` |
| `clock` | Безопасное, временно clamped или unsafe effective UTC |
| `dueRecords` | Физически присутствующие записи после active boundary |
| `historyRecords` | Количество сохранённых закрытых lifecycle snapshots |
| `pendingProjections` | Mutable projections, ожидающие convergence |
| `dueBacklogAgeMs` | Возраст самой старой due-записи, ещё не перенесённой в history |
| `artifacts` | Aggregate stored/due/history counts по артефактам |

`DEGRADED` допустим только для краткого recoverable clamp или convergence lag.
`DOWN` после clock rollback означает fail-closed: исправьте часы хоста и
перезапускайте сервис только после восстановления достоверного UTC. Не правьте
lifecycle timestamps или control rows вручную.

## Retention и capacity

Expiration и retention независимы. Expiration немедленно прекращает активное
использование; history retention позднее удаляет audit snapshots индексированными
bounded batches. Receipt retention управляет только кэшем ETL-skipping
confirmation. Удаление history не сбрасывает lifecycle/public-ID allocators.

Defaults рассчитаны на десятки тысяч и порядок ста тысяч active rows:

- `backstop-interval: 5s` повторяет correctness-проверку при потере deadline hint;
- `batch-size: 1000` ограничивает одну SQLite writer transaction;
- history и receipt retention равны `30d`.

Перед изменением batch size используйте aggregate health и query-plan/load
evidence. Не создавайте job на каждую запись и не используйте размер SQLite-файла
как доказательство retention: удалённые страницы могут оставаться в файле до
обслуживания БД.

## Граница rollback

До activation обычный application rollback может вернуть matching binary и
configuration, сохранив additive database schema. После activation rollback
означает остановку сервиса и совместное восстановление matching pre-activation
application, configuration и **обеих** SQLite databases. Восстановление только
одной БД или только YAML создаёт неподдерживаемую смешанную точку.

Generated CSV/export можно перестроить, но уже доставленные remote files и
sources, перемещённые после backup, требуют отдельной reconciliation.

## Проверка на disposable host

Repository-local проверка не требует root:

```bash
make lifecycle-smoke
make lifecycle-load
```

Harness подаёт данные через обычный daemon ingestion, проверяет переход
active-to-history, bounded retention, projection convergence и отсутствие ID
reuse, а environment/query-plan/throughput report сохраняет под `.dev/`.
Финальный release candidate всё равно требует packaging
fresh-install/upgrade/rollback проверки на disposable systemd host.

См. также [deployment guide](deployment.md),
[configuration reference](configuration.md) и
[daemon runbook](daemon-operations.md).
