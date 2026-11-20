# Артефакт IOC aggregate

[English version](../ioc-aggregate.md)

`ioc_aggregate` собирает IPv4, домены, URL и файловые хеши из принятых документов
или явно настроенных CSV deliveries в один canonical artifact и фид. Он работает
в обычных oneshot и daemon flows; это не отдельный runtime mode и не последующий
проход по таблицам других артефактов.

В одном фиде могут находиться разные типы IOC и данные разных источников.
Каждая строка по-прежнему описывает один IOC: сервис не связывает IP с доменом,
не выполняет DNS resolution, не объединяет несколько carriers в запись и не
выводит самостоятельно, что два наблюдения относятся к одной сущности.

## Публичные поля и идентичность

Порядок публичных колонок:

```text
name;ip_address;url_match;host_match;hash
```

Публичного `id` нет. Отсутствующие значения записываются настроенным CSV null
literal: `NULL` в поставляемой конфигурации. Каждая текущая строка содержит
ровно один IOC:

- bare IPv4 в `ip_address`;
- полный URL либо адрес без схемы, но с path/query/port в `url_match`;
- чистый FQDN/domain без scheme/port/path в `host_match`;
- MD5, SHA-1 или SHA-256 в верхнем регистре в `hash`.

Колонки hash algorithm нет; корректные MD5/SHA-1/SHA-256 содержат
соответственно 32/40/64 шестнадцатеричных символа.

Здесь `url_match` хранит текст адреса, а не mask-policy codes вроде `u:hEX`.
`lower-host` приводит scheme/host к нижнему регистру, сохраняя case path/query/
fragment. Он не добавляет схему и не сокращает URL до host. Detection и structural
validation следуют поддерживаемому parser/regex contract, а не полной грамматике
всех Internet addresses.

Canonical identity — четыре нормализованные carrier-колонки. `name` берётся из
source attribution документа либо явно mapped колонки managed import.
Более поздняя durable registration может заменить `name`; позднее пустое имя
сохраняет существующее значение. IP/domain/URL не коррелируются между собой.
Тот же carrier tuple с новым именем обновляет одну строку; изменение carrier
создаёт другую identity. URL и отдельно наблюдавшийся bare host остаются двумя
записями. `name` — выбранная атрибуция, а не stable ID или список всех ранее
сообщавших источников. У артефакта нет reusable export slot.
Сохранение имени относится к matched active row; expiry и повторное появление
подчиняются обычным правилам нового lifecycle.
Правило одного carrier принадлежит этому mapping/import contract, а не generic
storage. Для нескольких carriers нужен проверенный versioned contract и identity
transition; ослабление cardinality само по себе не реализует корреляцию.

## Настройка артефакта и маршрута

Текущие classpath и production templates включают generation и задают export
profile `ioc-aggregate`. Новые modules/dependencies сверх обычного bootable app
не нужны. Managed import выключен независимо. Существующая установка сохраняет
external YAML: новый template не добавляет элементы в operator-owned lists
автоматически.

| Конфигурация | Необходимая связь |
|---|---|
| `ioc.sink.artifacts` | Включённый `ioc_aggregate`, path, шесть типов, columns и write policy |
| `ioc.processing.document-plan` / `ioc.processing.plans` | Выбранный plan направляет observations в artifact; штатный `original-document` использует branch `aggregate-original` с `default-view: original` |
| `ioc.artifact-identity.artifacts` | Composite key из четырёх carriers, `record-key: ioc-aggregate-row-v1`, match key `ioc-aggregate-v1` |
| `ioc.export.profiles` | Profile `ioc-aggregate`, `output-mode: complete`, `artifacts: [ ioc_aggregate ]`; export и JDBC storage должны быть доступны |
| `ioc.dataframe-import` | Отдельный каталог source/authority/contract для CSV input |

Это **один artifact entry**, а не полный replacement application YAML.
Сохраните остальные entries и referenced plans в candidate:

```yaml
- name: ioc_aggregate
  enabled: true
  path: "./dataframe/IOC_aggregate_generated.csv"
  accepts: [ IPV4, DOMAIN, URL, MD5, SHA1, SHA256 ]
  columns:
    - { name: name,       from: source.label }
    - { name: ip_address, from: value, when-type: IPV4, when: [ is-bare-ip ] }
    - { name: url_match,  from: value, when-types: [ IPV4, DOMAIN, URL ], when: [ is-address-with-detail ], transform: [ lower-host ] }
    - { name: host_match, from: value, when-type: DOMAIN, when: [ is-clean-host ], transform: [ lower-host ] }
    - { name: hash,       from: value, when-types: [ MD5, SHA1, SHA256 ], transform: [ upper ] }
  write-policy:
    duplicate-selection: last-nonempty
    selection-column: name
    fields:
      - { name: name, update: latest-registered, empty: keep-existing }
```

Boot заменяет переопределённый list целиком; partial indexed overlay не является
обновлением по имени. Проверьте полный candidate через `ioc-config check`.
При host-cleanup routes других artifacts сохраняйте original view для aggregate:
смена view меняет будущие carriers/identity, а не существующие строки. См.
[processing routes](ioc-processing-routes.md).

Чтобы прекратить новые записи aggregate, согласованно измените enabled artifact,
plan references и export/import catalogs, затем проверьте конфигурацию.
Отключение generation не удаляет canonical rows и не переписывает старые exports.

## Генерация и использование фида

Для изолированного примера возьмите новый рабочий каталог вне namespace
установленного сервиса, trusted JAR и окружение без external config overlays.
Сохраните файл `sample.html`:

```html
<html><head><meta charset="utf-8"></head><body>
<h2>БИБ-0001</h2>
<p>192.0.2.10</p>
<p>Example.org</p>
<p>hxxps[:]//Example.org/Case.exe?Token=AbC</p>
<p>d41d8cd98f00b204e9800998ecf8427e</p>
</body></html>
```

Укажите реальный JAR в `APP_JAR`, затем выполните:

```bash
APP_JAR=/absolute/path/to/ioc-app.jar
java -jar "${APP_JAR}" extract --source ./sample.html
java -jar "${APP_JAR}" export --profile ioc-aggregate
```

Четыре observations дают отдельные строки с атрибуцией `БИБ-0001`: bare IP,
clean host, полный refanged URL и uppercase MD5. URL не создаёт дополнительный
derived host/IP. SHA-1/SHA-256 при обнаружении используют ту же hash-колонку.
Обычная extraction независимо может заполнять другие configured artifacts.

| Выход | Использование |
|---|---|
| `dataframe/IOC_aggregate_generated.csv` | Mutable projection canonical SQLite; следующие принятые runs накапливают/обновляют данные |
| `var/export/ioc-aggregate/<slice>/IOC_aggregate_generated.csv` | Completed immutable slice профиля с manifest/availability marker; для доставки используйте весь завершённый slice |

Export не парсит документы заново и не объединяет другие CSV. Повторный запрос
может быть skipped, если plan/revisions/bytes уже покрыты. Изменившийся public
`name` продвигает revision; origin-only metadata или обычное TTL renewal — нет.
Profiles выбирают artifacts, а не подмножества по name/source. Для SMB delivery
явно разрешите этот profile на publish target; другие profiles его автоматически
не включают. См. [export mechanics](../../dev/ru/artifact-export.md) и
[remote storage](remote-storage-sync.md).

При fixed lifecycle expiry убирает active aggregate membership и обновляет
mutable projection. Одна expiry не принуждает создание нового immutable slice;
consumer должен учитывать export/lifecycle cadence. Публичный feed не содержит
validity deadline. См. [canonical lifecycle](canonical-record-lifecycle.md).

## Перед первым запуском обновлённой версии

Для существующей установки готовьте upgrade как согласованный переход:

1. На старой версии прекратите подачу document и managed-import файлов.
2. Дождитесь terminal state всех claimed document runs и import deliveries.
   Проверьте daemon health, `ioc import status`, document `processing` и
   managed-import processing/staging directories. Не выводите старый cross-path
   priority из timestamps.
3. Остановите сервис. Как одну recovery point сохраните effective configuration,
   весь `var/db` с обеими SQLite DB/sidecars и service-owned processing/snapshot/
   staging files.
4. Установите binary, согласуйте external YAML с template и выполните
   `ioc-config check` до применения.
5. До возврата producer access дождитесь healthy storage, lifecycle, ingestion
   и observation-registration components.
6. Подайте representative document и проверьте
   `dataframe/IOC_aggregate_generated.csv` и slice профиля `ioc-aggregate`.

Известный `OPS-10` может ломать import status/health на nullable deadline pending
delivery. Сбой запроса не доказывает ни пустую очередь, ни failed canonical
commit; сохраните recovery point и следуйте import runbook.

Startup fail-closed, если ordered-field policy включена, а legacy nonterminal
work не имеет durable registration. Остановите intake, восстановите matching
pre-upgrade recovery point либо сначала завершите старую работу с конфигурацией
без ordered policy. Приложение не выдаёт новый rank для обхода recovery checks.

## Первое включение и порядок обновлений

Upgrade не сканирует существующие canonical artifacts и не создаёт aggregate
rows задним числом. При первом добавлении aggregate table/projection не содержат
старых строк; их заполняют только принятые после активации observations.
Историческую популяцию подавайте заново осознанно: это новая delivery с новым
rank и обычными lifecycle effects. Обычный restart сохраняет aggregate state.
Generated CSV и samples в `source` не являются runtime lookup inputs.

Retry/restart recovery сохраняют исходный rank. Completion order не определяет
победителя: более поздний зарегистрированный непустой `name` остаётся приоритетным,
даже если старая delivery commit-ится позже. В одном документе или duplicate
group импортируемого CSV последнее принятое непустое имя выбирает всё occurrence;
поздние пустые имена его не очищают. Document selection и ordered-field candidates
проверяют nonblank. Новая строка extraction без source label имеет name NULL.
Cross-path precedence
задаётся registration, не source timestamps или именем import file; порядок
принадлежит dataframe DB.

## Managed import

Production template содержит contract `ioc-aggregate-v1` и authority
`ioc-aggregate-feed`, а global intake выключен. Classpath defaults имеют пустые
import catalogs. Сохраните полный нужный catalog в candidate. Daemon intake
требует fixed lifecycle и JDBC stores.

1. Настройте отдельный local/SMB source и разрешите только aggregate contract
   и authority profile.
2. Проверьте effective configuration.
3. До live intake проверьте representative files с enabled import catalog и fixed
   lifecycle в disposable oneshot configuration/storage copy.
4. Включайте daemon intake только после подготовки inbox, прав и retention.

Для настроенного source ID preview через installed CLI:

```bash
sudo /opt/ioc-extractor/bin/ioc import validate \
  --source aggregate-local --file /path/to/candidate.csv
```

`aggregate-local` — выбранный оператором source, не built-in. Его `contracts`
должен разрешать `ioc-aggregate-v1`, а `authority` ссылаться на
`ioc-aggregate-feed`. Штатный processed contract связывает
`original-aggregate-import` с явными input/output carriers, target-only routing
и без related-artifact authority. Сохраняйте named plan и при выключенном intake:
объявленные contracts проходят preflight.

Пример candidate rows:

```csv
name;ip_address;url_match;host_match;hash
Feed-A;192.0.2.10;NULL;NULL;NULL
Feed-B;NULL;https://example.org/Case.exe?Token=AbC;NULL;NULL
Feed-C;NULL;NULL;example.org;NULL
Feed-D;NULL;NULL;NULL;D41D8CD98F00B204E9800998ECF8427E
```

Input header — публичный контракт из пяти колонок. В каждой принятой строке
ровно один carrier непустой. `name` имеет `replace-non-null`; carriers проходят
strict structural validation и не могут направляться в другие artifacts.
Дубликаты проходят last-nonempty selection до canonical promotion.
Штатный `row-failure-policy: reject-delivery` отвергает delivery с invalid row.
Составная строка IP+URL отвергается, а не разделяется/связывается; отсутствующие
строки не удаляют данные. `renew-unchanged: true` разрешает обычное lifecycle
confirmation. Imported public ID/slot здесь не используется.

Известное ограничение (`OPS-14`): импортированный `name` из одних пробелов
остаётся VALUE cell. Он может обойти ordered-field candidate handling и заменить
существующее имя обычным merge `replace-non-null`. Это нарушает задуманное
сохранение непустого имени; для whitespace input текущая реализация его не
гарантирует. До исправления передавайте пустую ячейку или настроенный `NULL`,
а не пробельное заполнение. Для атрибуции передавайте настоящее nonblank имя.
Исправление учитывается в [известных проблемах](../../KNOWN-ISSUES.md).

Preview проверяет/maps отдельные строки, не duplicate reduction или active-DB
matching, и не выполняет canonical import. Полный CLI может создавать storage
и workspaces. Нужен enabled import: disabled/empty catalog не проверяет файл
по source name. Exit preview — `0` для valid либо `3` для invalid rows;
startup/configuration failures отдельны. См. [managed import](dataframe-import.md)
для stable candidates, null/presence и подачи настоящей delivery.

## Health и retention

Компонент `observationRegistration` публикует только aggregate counts.
Unresolved oneshot даёт `DOWN`, `pendingOneshot` и возраст старейшей unresolved
registration. Automatic retention её не удаляет: invocation может быть долгой
и живой. Сначала проверьте её process, сохраните либо восстановите coordinated
recovery point. Automatic re-ranking fallback нет; нельзя удалять internal rows
ради очистки health.

Terminal document/import registrations удаляются bounded batches только после
receipt/history horizon и при отсутствии active/history field origin,
canonical observation receipt или service recovery reference на них.

## Граница rollback

Feature добавляет durable ordering и field-origin state в обе DB. Текущие версии
определяются [dataframe migration catalog](../../../adapters/adapter-store-jdbc/src/main/java/com/iocextractor/adapter/out/store/jdbc/DataframeFormatMigrations.java)
и [service migration catalog](../../../adapters/adapter-store-jdbc/src/main/java/com/iocextractor/adapter/out/store/jdbc/ServiceSchemaMigrations.java),
а не фиксированным предположением v11/v11. Не удаляйте tables и не уменьшайте
SQLite `user_version`. Binary-only rollback не поддерживается.

Для rollback остановите intake/service, восстановите старые binary/external
configuration, обе DB и service-owned files из одной pre-upgrade recovery point,
затем запустите и проверьте health до открытия producers. Работа после backup
пропадёт из восстановленного state и требует отдельной reconciliation.
Не сочетайте новые dataframe DB со старыми service DB или наоборот.

## Связанные руководства

- [Справочник конфигурации](configuration.md)
- [Canonical record lifecycle](canonical-record-lifecycle.md)
- [Managed dataframe import](dataframe-import.md)
- [Развёртывание и rollback](deployment.md)

## Источники и сопровождение

- [Classpath defaults](../../../bootstrap/ioc-app/src/main/resources/application.yml)
  и [production template](../../../packaging/templates/application.yml) задают
  routes, identity, write policy, import contract и export profile.
- [Processing](../../dev/ru/processing.md), [storage](../../dev/ru/storage.md) и
  [ADR 0030, EN](../../ADR/0030-registered-observation-order-for-artifact-fields.md)
  объясняют preparation, public mutation и durable precedence.
- [Golden pipeline](../../../bootstrap/ioc-app/src/test/java/com/iocextractor/GoldenPipelineIT.java),
  [customer routes](../../../bootstrap/ioc-app/src/test/java/com/iocextractor/CustomerRoutingPipelineIT.java)
  и [export consumer](../../../bootstrap/ioc-app/src/test/java/com/iocextractor/OnDemandExportIntegrationIT.java)
  проверяют mapping/bytes. Выполняйте нужные suites и реальные изолированные
  примеры; `make docs` проверяет ссылки, не смысл. При изменении поведения,
  configuration, ordering и compatibility обновляйте обе версии.
