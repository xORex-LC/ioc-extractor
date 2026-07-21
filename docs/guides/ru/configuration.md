# Справочник конфигурации

Это полный операторский справочник конфигурации ioc-extractor. Он описывает
поддерживаемые параметры, допустимые значения и эксплуатационные компромиссы без
необходимости знать внутреннюю реализацию приложения.

Готовая основа production override находится в
[полном шаблоне application](../../../packaging/templates/application.yml).

## Как применяется конфигурация

В приложение встроены безопасные значения по умолчанию. Установленный сервис
загружает `<prefix>/etc/application.yml` как override, поэтому пропущенные
параметры сохраняют встроенные значения. Приоритет от низшего к высшему:

```text
встроенные defaults < внешний YAML < environment < JVM system properties < CLI
```

Всё пространство `ioc.*` строгое. Опечатка или удалённый параметр в YAML,
environment, system property либо командной строке останавливает запуск. Оператор
не должен ошибочно считать, что настройка безопасности или доставки применена,
когда приложение молча её проигнорировало.

После изменения установленного YAML или environment-файла перезапустите сервис.
Секреты передавайте через placeholders вида `${SMB_PASSWORD}` и не записывайте
их непосредственно в YAML.

### Синтаксис значений

- Duration задаётся в Spring-формате: `500ms`, `10s`, `5m`, `2h`, `7d`.
- Boolean: `true` или `false`.
- Пути могут быть абсолютными или относительными к рабочему каталогу процесса.
  Установленный сервис использует installation prefix как рабочий каталог.
- Списки задаются YAML-последовательностью или inline: `[ "*.html", "*.docx" ]`.
- Environment names используют uppercase underscore form, например
  `ioc.pipeline.failure-policy` → `IOC_PIPELINE_FAILURE_POLICY`. Для индексированных
  списков предпочитайте YAML: частичный override элемента может отбросить его
  соседние поля из источника с меньшим приоритетом.

## Runtime и observability

| Параметр | Тип / значения | Встроенный default | Рекомендация |
|---|---|---|---|
| `ioc.runtime.mode` | `oneshot`, `daemon` | `oneshot` | `daemon` предназначен для долгоживущего systemd-сервиса. |
| `ioc.observability.mode` | `oneshot`, `daemon` | `oneshot` | Держите согласованным с runtime mode. `daemon` включает rolling ECS JSON logs. |
| `ioc.observability.per-item-trace-enabled` | boolean | `false` | Высокообъёмный TRACE для каждого индикатора. Включайте только для ограниченной диагностики. |

## Хранилища

Dataframe database — канонический источник бизнес-данных. Service database
содержит ingestion/export/sync ledgers. SQLite создаёт `-wal` и `-shm` рядом с
основным файлом; копируйте весь `var/db` при остановленном сервисе либо применяйте
согласованную SQLite backup procedure.

| Параметр | Тип / значения | Встроенный default | Рекомендация |
|---|---|---|---|
| `ioc.storage.service.type` | `jdbc` | `jdbc` | Поддерживается JDBC/SQLite service store. |
| `ioc.storage.service.url` | JDBC URL | `jdbc:sqlite:./var/db/ioc-service.db` | Не объединяйте с dataframe DB. |
| `ioc.storage.service.sqlite.tuning` | `low-memory`, `balanced`, `high-throughput` | `low-memory` | `low-memory` для ограниченных/shared hosts, `balanced` при измеренном запасе памяти, `high-throughput` только на dedicated high-memory host после load testing. |
| `ioc.storage.service.pool.write-max` | положительное целое | `1` | Для single-writer модели SQLite оставляйте `1`. |
| `ioc.storage.service.pool.read-max` | положительное целое | `2` | Увеличивайте только после измерения конкурентных чтений ledger. |
| `ioc.storage.dataframe.type` | `jdbc` | `jdbc` | Каноническое JDBC/SQLite storage. |
| `ioc.storage.dataframe.url` | JDBC URL | `jdbc:sqlite:./var/db/ioc-dataframe.db` | Файл является источником бизнес-данных. |
| `ioc.storage.dataframe.sqlite.tuning` | `low-memory`, `balanced`, `high-throughput` | `low-memory` | Большие presets полезнее dataframe scans, но учитывайте JVM native memory и systemd limits. |
| `ioc.storage.dataframe.pool.write-max` | положительное целое | `1` | Для SQLite оставляйте `1`. |
| `ioc.storage.dataframe.pool.read-max` | положительное целое | `2` | Повышайте только после load testing. |

## Политика pipeline

| Параметр | Тип / значения | Встроенный default | Рекомендация |
|---|---|---|---|
| `ioc.pipeline.deduplicate` | boolean | `true` | Удаляет повторы внутри одного batch. Межзапусковая дедупликация остаётся в canonical storage. |
| `ioc.pipeline.failure-policy` | `fail-fast`, `collect-and-continue` | `fail-fast` | Для unattended daemon используйте `collect-and-continue`: валидные строки могут быть записаны при итоговом отчёте об ошибках. |
| `ioc.pipeline.max-diagnostics-per-run` | положительное целое | `10000` | Ограничивает память и объём вывода. Уменьшайте на малых хостах или для шумных недоверенных inputs. |

## Чтение источника

| Параметр | Тип / значения | Встроенный default | Рекомендация |
|---|---|---|---|
| `ioc.source.type` | `auto` | `auto` | Автоматическое определение формата документа. |
| `ioc.source.charset` | `auto` или Java charset | `auto` | Для legacy HTML/text задавайте `windows-1251` только при ошибке detection. DOCX/PDF параметр игнорируют. |
| `ioc.source.section-markers` | список RE2-compatible regex | встроенные markers российских источников | Совпавший заголовок становится source label следующих IOC. Порядок сохраняется. |

## Refang и detection

Refang rules применяются по порядку: более специфичные замены располагайте перед
короткими префиксами. Detection patterns должны быть RE2-compatible — без
look-around и back-reference.

| Параметр | Тип / значения | Встроенный default | Рекомендация |
|---|---|---|---|
| `ioc.refang.rules` | упорядоченный список | типовые defang replacements | При изменении переопределяйте список полностью. |
| `ioc.refang.rules[].from` | строка | обязателен в rule | Литеральный заменяемый текст. |
| `ioc.refang.rules[].to` | строка | обязателен в rule | Литеральная замена. |
| `ioc.engine` | `re2j`, `jdk` | `re2j` | Предпочитайте linear-time `re2j`; `jdk` — compatibility fallback. |
| `ioc.patterns` | map с ключами `IPV4`, `DOMAIN`, `URL`, `MD5`, `SHA1`, `SHA256` | встроенные patterns | Порядок map задаёт приоритет extraction. URL/IP должны выигрывать пересекающиеся domain spans. |

## Классификация сети

Правила вычисляются по first-match-wins. Пустой `when` — финальный fallback.
Доступны `has-query`, `has-path`, `has-port`, `has-path-or-port`, `is-ip`,
`is-registrable`, `is-subdomain`, `is-onion`.

| Параметр | Тип / значения | Встроенный default | Рекомендация |
|---|---|---|---|
| `ioc.classify.rules` | упорядоченный список | четыре production rules | Переопределяйте весь порядок целиком. |
| `ioc.classify.rules[].when` | список predicates | обязателен, может быть пустым | В одном rule должны выполниться все predicates. |
| `ioc.classify.rules[].url-match` | непустая строка | обязателен | URL match code целевой reputation system. |
| `ioc.classify.rules[].host-match` | строка или null | опционален | Для URL-only variants используйте null. |

## CSV и artifacts

Встроенные artifacts — production defaults. Обычно оператор меняет пути,
charset или enabled. Schema/identity changes меняют durable contract и должны
проверяться на существующей DB и consumers.

| Параметр | Тип / значения | Встроенный default | Рекомендация |
|---|---|---|---|
| `ioc.sink.csv.delimiter` | один символ | `;` | Должен отличаться от quote. |
| `ioc.sink.csv.quote` | один символ | `"` | Должен отличаться от delimiter. |
| `ioc.sink.csv.null-literal` | непустая строка | `NULL` | Сериализованное отсутствие значения. |
| `ioc.sink.csv.charset` | Java charset | `UTF-8` | Непредставимые символы заменяются и диагностируются, но не останавливают run. |
| `ioc.sink.artifacts` | непустой список | masks, ip_list, address_blacklist, hashes | Переопределяйте полные элементы, не отдельные indexes. |
| `ioc.sink.artifacts[].name` | уникальная строка | обязателен | Stable identity, используемая export и row-key config. |
| `ioc.sink.artifacts[].enabled` | boolean | обязателен | Disabled artifact не готовится и не проецируется. |
| `ioc.sink.artifacts[].path` | путь | обязателен | Путь mutable projection. |
| `ioc.sink.artifacts[].accepts` | список IOC types | обязателен | `IPV4`, `DOMAIN`, `URL`, `MD5`, `SHA1`, `SHA256`. |
| `ioc.sink.artifacts[].include` | список filters | опционален | Должны совпасть все include predicates; также доступен `is-bare-ip`. |
| `ioc.sink.artifacts[].exclude` | список filters | опционален | Совпавший predicate исключает строку; доступен `is-bare-ip`. |
| `ioc.sink.artifacts[].id` | object | опционален | Нужен, если column использует deferred provider `id`. |
| `ioc.sink.artifacts[].id.strategy` | `ascending`, `descending` | `ascending` в defaults | Направление public ID allocation. |
| `ioc.sink.artifacts[].id.start` | `auto` или signed 64-bit integer | `auto` | `auto` продолжает durable baseline; failed reservations не переиспользуются. |
| `ioc.sink.artifacts[].columns` | непустой упорядоченный список | зависит от artifact | Задаёт CSV header order и canonical columns. |
| `ioc.sink.artifacts[].columns[].name` | непустая строка | обязателен | Уникальное имя output column внутри artifact. |
| `ioc.sink.artifacts[].columns[].from` | provider | обязателен | `id`, `value`, `source.label`, `match.url`, `match.host`, `address.url`, `address.ip`, `const`. |
| `ioc.sink.artifacts[].columns[].value` | строка или null | опционален | Literal для `const`. |
| `ioc.sink.artifacts[].columns[].type` | `TEXT`, `INTEGER`, `REAL`, `BLOB`, `NUMERIC` | inferred/text | SQLite affinity, прежде всего для constant columns. |
| `ioc.sink.artifacts[].columns[].when-type` | IOC type | опционален | Provider выводится только для выбранного IOC type. |
| `ioc.sink.artifacts[].columns[].transform` | упорядоченный список | опционален | `lower`, `lower-host`, `upper`, `strip-prefix:<text>`. Для deferred `id` запрещён. |

## Canonical artifact identity

Каждому enabled sink artifact соответствует один identity entry. Key columns
должны существовать в columns этого artifact. Смена identity на заполненной DB
защищена как schema drift.

| Параметр | Тип / значения | Встроенный default | Рекомендация |
|---|---|---|---|
| `ioc.artifact-identity.artifacts` | непустой список | по entry на built-in artifact | Согласуйте names с sink artifacts. |
| `ioc.artifact-identity.artifacts[].name` | artifact name | обязателен | Ссылка на `ioc.sink.artifacts[].name`. |
| `ioc.artifact-identity.artifacts[].key-columns` | непустой список | зависит от artifact | Значения, формирующие canonical row key. |
| `ioc.artifact-identity.artifacts[].key-mode` | `first-non-empty` или omitted | omitted | Для альтернативных колонок, например разных hash algorithms. |
| `ioc.artifact-identity.artifacts[].epoch` | positive integer или omitted | omitted | Явная generation identity schema для контролируемой migration. |

## Immutable export

| Параметр | Тип / значения | Встроенный default | Рекомендация |
|---|---|---|---|
| `ioc.export.enabled` | boolean | `true` | Управляет manual и daemon export. |
| `ioc.export.root` | путь | `./var/export` | Immutable slices, staging и export ledgers. |
| `ioc.export.trigger.type` | `interval`, `quiet-period` | `interval` | `quiet-period` coalesces changes; periodic check остаётся recovery backstop. |
| `ioc.export.trigger.interval` | positive duration | `5m` | Scheduler/reconcile cadence. |
| `ioc.export.trigger.quiet-period` | positive duration | `5m` | Задержка после последнего canonical change. |
| `ioc.export.trigger.max-cap` | positive duration | `1h` | Максимальная отсрочка при непрерывном ingestion. |
| `ioc.export.profiles` | непустой список | reputation-lists, address-blacklist | Упорядоченные неделимые наборы artifacts. |
| `ioc.export.profiles[].name` | уникальная строка | обязателен | Используется CLI и sync targets. |
| `ioc.export.profiles[].output-mode` | `complete`, `append` | `complete` | В этом release выполняется только `complete`; `append` зарезервирован и отклоняется. |
| `ioc.export.profiles[].artifacts` | непустой список | зависит от profile | Ссылки на enabled sink artifacts. |
| `ioc.export.retention.max-age` | duration или null | `7d` | Удаляет unpinned slices старше лимита. |
| `ioc.export.retention.max-count` | неотрицательное целое | `3` | Максимум новых unpinned slices; `0` отключает count limit. |

## Remote synchronization

По умолчанию sync выключен. Credentials хранятся в environment file. Права,
topology, failure behavior и подбор значений описаны в
[гайде remote storage](remote-storage-sync.md).

| Параметр | Тип / значения | Встроенный default | Рекомендация |
|---|---|---|---|
| `ioc.sync.enabled` | boolean | `false` | Master switch sync graph. |
| `ioc.sync.retry.max-attempts` | integer ≥ 1 | `3` | Общее число попыток recoverable remote operation. |
| `ioc.sync.retry.backoff` | positive duration | `1s` | Начальная retry delay. |
| `ioc.sync.retry.multiplier` | decimal ≥ 1.0 | `2.0` | Exponential growth factor. |
| `ioc.sync.retry.max-backoff` | duration ≥ backoff | `30s` | Ограничение retry delay. |
| `ioc.sync.retry.jitter` | boolean | `true` | Оставляйте включённым при возможных одновременных retries. |
| `ioc.sync.endpoints` | список | пустой | Именованные connections для fetch/publish. |
| `ioc.sync.endpoints[].name` | уникальная строка | обязателен | Stable reference источников и targets. |
| `ioc.sync.endpoints[].transport` | `smb` | обязателен | Сейчас поддерживается SMB. |
| `ioc.sync.endpoints[].smb` | object | обязателен для SMB | Connection settings. |
| `ioc.sync.endpoints[].smb.host` | hostname или IP | обязателен | Без префикса `smb://`. |
| `ioc.sync.endpoints[].smb.share` | share name | обязателен | Имя верхнеуровневой шары без пути. |
| `ioc.sync.endpoints[].smb.domain` | string или null | опционален | AD/NTLM domain; пропустите для local/workgroup account. |
| `ioc.sync.endpoints[].smb.username` | string | обязателен | Предпочтительно `${SMB_USER}`. |
| `ioc.sync.endpoints[].smb.password` | string | обязателен | Используйте `${SMB_PASSWORD}`, не commit plaintext. |
| `ioc.sync.endpoints[].smb.encrypt` | boolean | `true` в template | Требовать SMB3 encryption при поддержке сервером. |
| `ioc.sync.endpoints[].smb.connect-timeout` | positive duration | `10s` | TCP connection establishment timeout. |
| `ioc.sync.endpoints[].smb.request-timeout` | positive duration | `30s` | Timeout одного SMB request. |
| `ioc.sync.endpoints[].smb.idle-timeout` | positive duration | `5m` | Закрытие неиспользуемого cached client. |
| `ioc.sync.fetch.enabled` | boolean | `false` | Remote detection и download. |
| `ioc.sync.fetch.interval` | positive duration | `1m` | Correctness polling cadence, не отключаемый push notifications. |
| `ioc.sync.fetch.sources` | список | пустой | Наблюдаемые remote directories. |
| `ioc.sync.fetch.sources[].name` | уникальная строка | обязателен | Fetch identity и ledger scope. |
| `ioc.sync.fetch.sources[].endpoint` | endpoint name | обязателен | Ссылка на endpoint. |
| `ioc.sync.fetch.sources[].remote-path` | share-relative path | обязателен | Каталог source documents. |
| `ioc.sync.fetch.sources[].include` | glob list | пустой | Разрешённые имена; пустой список не ограничивает include. |
| `ioc.sync.fetch.sources[].exclude` | glob list | пустой | Исключённые имена; exclusions имеют приоритет. |
| `ioc.sync.fetch.sources[].change-notify` | object | опционален | SMB2 push accelerator; polling остаётся backstop. |
| `ioc.sync.fetch.sources[].change-notify.enabled` | boolean | `false` | Включайте после проверки совместимости сервера. |
| `ioc.sync.fetch.sources[].change-notify.debounce` | positive duration | `3s` | Объединяет burst сигналов перед re-detection. |
| `ioc.sync.publish.enabled` | boolean | `false` | Delivery завершённых immutable slices. |
| `ioc.sync.publish.interval` | positive duration | `5m` | Publish-ledger reconciliation cadence. |
| `ioc.sync.publish.targets` | список | пустой | Delivery destinations. |
| `ioc.sync.publish.targets[].name` | уникальная строка | обязателен | Publish ledger identity. |
| `ioc.sync.publish.targets[].endpoint` | endpoint name | обязателен | Ссылка на endpoint. |
| `ioc.sync.publish.targets[].remote-path` | share-relative path | обязателен | Родительский каталог immutable slices. |
| `ioc.sync.publish.targets[].export-profile` | export profile name | обязателен | Ссылка на `ioc.export.profiles[].name`. |

## Daemon ingestion

Четыре ingestion directories должны различаться. Размещайте их на одной локальной
filesystem, чтобы claims использовали atomic move. Concurrency `1` — поддерживаемая
база для SQLite и детерминированной обработки.

| Параметр | Тип / значения | Встроенный default | Рекомендация |
|---|---|---|---|
| `ioc.ingestion.dirs.inbox` | путь | `./var/inbox` | Сюда оператор помещает завершённые или ещё копирующиеся files. |
| `ioc.ingestion.dirs.processing` | путь | `./var/processing` | Claimed work, принадлежащий daemon. |
| `ioc.ingestion.dirs.done` | путь | `./var/done` | Успешно завершённые inputs. |
| `ioc.ingestion.dirs.failed` | путь | `./var/failed` | Terminal failures для анализа или requeue. |
| `ioc.ingestion.patterns.include` | непустой glob list | HTML, HTM, DOCX | PDF и другие supported formats добавляются явно. |
| `ioc.ingestion.patterns.exclude` | glob list | temp/partial/hidden | Exclusions имеют приоритет. |
| `ioc.ingestion.detect.use-watch-service` | boolean | `true` | Low-latency local hint. На ненадёжной network filesystem отключите; reconcile остаётся authoritative. |
| `ioc.ingestion.detect.reconcile-interval` | positive duration | `30s` | Directory polling backstop. Меньше — ниже latency, больше scanning. |
| `ioc.ingestion.detect.max-messages-per-poll` | positive integer | `50` | Ограничивает claims одного detection cycle. |
| `ioc.ingestion.stability.quiet-period` | positive duration | `10s` | Увеличьте для медленного копирования; слишком мало — риск incomplete file. |
| `ioc.ingestion.retry.max-attempts` | positive integer | `3` | Попытки до перемещения в failed. |
| `ioc.ingestion.retry.backoff` | positive duration | `5s` | Задержка local ingestion retry. |
| `ioc.ingestion.ledger.type` | `file`, `jdbc` | `file` | `jdbc` для durable run-ledger integration; следуйте deployment baseline. |
| `ioc.ingestion.ledger.path` | путь | `./var/ledger` | Filesystem ledger при type `file`. |
| `ioc.ingestion.concurrency` | integer (reserved seam) | `1` | Оставляйте `1`: в 0.2.0 channel синхронный и параметр не включает parallel ingestion. |

## Maintenance retention

Leaf-file retention применяется к `done`, `failed` и другим configured dirs.
Immutable export slices используют отдельную retention policy.

| Параметр | Тип / значения | Встроенный default | Рекомендация |
|---|---|---|---|
| `ioc.maintenance.retention.enabled` | boolean | `false` | Включайте после определения backup и investigation requirements. |
| `ioc.maintenance.retention.interval` | positive duration | `1h` | Sweep cadence. |
| `ioc.maintenance.retention.initial-delay` | non-negative duration | `5m` | Задержка после daemon startup. |
| `ioc.maintenance.retention.targets` | список | примеры done/failed | Policies для recursively found leaf files. |
| `ioc.maintenance.retention.targets[].name` | уникальная строка | обязателен | Operator-facing identity policy. |
| `ioc.maintenance.retention.targets[].dir` | путь | обязателен | Никогда не указывайте DB или export root. |
| `ioc.maintenance.retention.targets[].max-age` | duration или null | зависит от target | Entry eligible по age либо count limit. |
| `ioc.maintenance.retention.targets[].max-count` | non-negative integer | `0` | `0` отключает count retention. |
| `ioc.maintenance.retention.targets[].action` | `delete`, `archive` | `delete` | Archive восстановим, но требует дополнительное storage. |
| `ioc.maintenance.retention.targets[].archive-dir` | путь или null | omitted | Обязателен для `archive`, вне swept directory. |

## Поддерживаемые platform settings

Эти Spring settings входят в эксплуатационный контракт. Другие Spring Boot
properties не становятся поддерживаемыми только потому, что framework их знает.

| Параметр | Встроенный default | Рекомендация |
|---|---|---|
| `spring.profiles.active` | из observability mode | Installed daemon использует `daemon`. |
| `spring.config.additional-location` | задаёт launcher | Сохраняйте `optional:file:./etc/application.yml`. |
| `spring.main.web-application-type` | `none`, launcher задаёт `servlet` для daemon | Launcher-owned; не переопределяйте в installed YAML. |
| `logging.level.root` | `INFO` | `DEBUG`/`TRACE` только для ограниченной диагностики. |
| `logging.level.com.iocextractor` | `INFO` | Детализация application logs. |
| `logging.file.path` | `var/logs` | Rolling ECS JSON directory в daemon mode. |
| `server.address` | `127.0.0.1` | Оставляйте loopback без authentication/exposure policy. |
| `server.port` | `8081` | Actuator port для health checks. `deploy-local.sh --port` и `install.sh --server-port` рендерят более приоритетный CLI override. |
| `server.tomcat.threads.max` | `8` | Достаточно для loopback actuator-only surface. |
| `server.tomcat.threads.min-spare` | `1` | Минимум idle request threads. |
| `server.tomcat.accept-count` | `10` | Небольшая очередь connections. |
| `management.endpoints.web.exposure.include` | `health,info` | Не включайте shutdown и broad actuator exposure. |
| `management.endpoint.health.show-details` | `always` | Безопасно только при loopback-only endpoint. |

## Environment-файл

`<prefix>/etc/ioc-extractor.env` читают systemd и установленный `ioc` launcher.

| Переменная | Назначение |
|---|---|
| `JAVA_HOME` | Опциональный JDK override для launcher. |
| `JAVA_OPTS` | JVM memory, GC и diagnostic options; не помещайте сюда application CLI arguments. |
| `SMB_USER` | Username для `${SMB_USER}`. |
| `SMB_PASSWORD` | Password для `${SMB_PASSWORD}`; installer задаёт `0640`, чтение только root и service group. |

После изменения YAML/environment выполните `systemctl restart ioc-extractor`,
проверьте `ioc health` и `journalctl -u ioc-extractor`.
